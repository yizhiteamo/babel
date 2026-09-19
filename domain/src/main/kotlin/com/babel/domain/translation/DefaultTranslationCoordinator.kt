package com.babel.domain.translation

import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.common.Redact
import com.babel.core.model.LanguagePair
import com.babel.core.model.RenderedTranslation
import com.babel.core.model.RequestId
import com.babel.core.model.StyleHints
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationRuntimeState
import com.babel.core.model.TranslationStatus
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.language.LanguageResolver
import com.babel.domain.privacy.SensitiveContentPolicy
import com.babel.domain.render.RenderUpdate
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.SettingsRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The pipeline's single decision point.
 *
 * **Tracked state is mutated only from the mailbox coroutine.** Translations
 * run concurrently, but they report back as [Message.Translated] rather than
 * touching the map directly — otherwise the revision comparison that rejects
 * stale results would race with the acquisition events that bump revisions.
 *
 * The map is still concurrent because [stop] tears state down from whichever
 * thread called it, after the mailbox coroutine has been cancelled.
 */
class DefaultTranslationCoordinator(
    private val translator: Translator,
    private val cache: TranslationCache,
    private val languageResolver: LanguageResolver,
    private val settingsRepository: SettingsRepository,
    private val sensitivePolicy: SensitiveContentPolicy,
    private val dispatchers: DispatcherProvider,
    private val logger: BabelLogger = BabelLogger.NoOp,
    private val config: CoordinatorConfig = CoordinatorConfig(),
) : TranslationCoordinator {

    private val _runtimeState = MutableStateFlow<TranslationRuntimeState>(
        TranslationRuntimeState.Disabled,
    )
    override val runtimeState: StateFlow<TranslationRuntimeState> = _runtimeState.asStateFlow()

    private val _renderUpdates = MutableSharedFlow<RenderUpdate>(
        replay = 0,
        extraBufferCapacity = RENDER_BUFFER,
    )
    override val renderUpdates: Flow<RenderUpdate> = _renderUpdates.asSharedFlow()

    /** Never closed, so the coordinator can be restarted after [stop]. */
    private val mailbox = Channel<Message>(Channel.UNLIMITED)

    private val requestIds = AtomicLong(0)
    private val permits = Semaphore(config.maxConcurrentTranslations)

    @Volatile
    private var scope: CoroutineScope? = null

    private val tracked = ConcurrentHashMap<TextElementId, Tracked>()

    @Volatile
    private var languages: LanguagePair? = null

    override fun start() {
        // Resuming from pause is not a restart: keep what is already on screen.
        if (_runtimeState.value == TranslationRuntimeState.Paused) {
            _runtimeState.value = TranslationRuntimeState.Running
            return
        }
        if (scope != null) return

        _runtimeState.value = TranslationRuntimeState.Starting
        val newScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        scope = newScope

        newScope.launch {
            tracked.clear()
            languages = resolveLanguages(settingsRepository.settings.first())
            _runtimeState.value = TranslationRuntimeState.Running
            launch { observeSettings() }
            processMailbox()
        }
    }

    override fun pause() {
        if (_runtimeState.value != TranslationRuntimeState.Running) return
        _runtimeState.value = TranslationRuntimeState.Paused
        // In-flight work is abandoned; what is already rendered stays there,
        // because the user asked to pause, not to clear.
        mailbox.trySend(Message.CancelInFlight)
    }

    override fun stop() {
        val active = scope ?: return
        scope = null
        _runtimeState.value = TranslationRuntimeState.Disabled

        active.cancel()
        tracked.values.forEach { it.job?.cancel() }
        tracked.clear()
        languages = null
        drainMailbox()

        // Emitted after cancelling the scope so the renderer still hears it.
        _renderUpdates.tryEmit(RenderUpdate.ClearAll)
    }

    override fun submit(event: TextSourceEvent) {
        if (scope == null) return
        mailbox.trySend(Message.Source(event))
    }

    // ---------------------------------------------------------------- mailbox

    private suspend fun processMailbox() {
        for (message in mailbox) {
            when (message) {
                is Message.Source -> onSourceEvent(message.event)
                is Message.Translated -> onTranslated(message)
                is Message.Failed -> onFailed(message)
                is Message.SettingsChanged -> onSettingsChanged(message.settings)
                Message.CancelInFlight -> cancelInFlight()
            }
        }
    }

    private suspend fun onSourceEvent(event: TextSourceEvent) {
        if (_runtimeState.value == TranslationRuntimeState.Paused) {
            // Removals still apply while paused: content that left the screen
            // must not keep an overlay alive.
            when (event) {
                is TextSourceEvent.Removed -> onRemoved(event.ids)
                is TextSourceEvent.Cleared -> onCleared(event.sourceType)
                is TextSourceEvent.Upserted -> onUpserted(event.elements.filterNot(::isPausedSource))
            }
            return
        }

        when (event) {
            is TextSourceEvent.Upserted -> onUpserted(event.elements)
            is TextSourceEvent.Removed -> onRemoved(event.ids)
            is TextSourceEvent.Cleared -> onCleared(event.sourceType)
        }
    }

    /**
     * Whether pausing applies to where this text came from.
     *
     * Pause is the text path's switch — that is what the control offering it
     * says, and what a user pressing it means. Manga mode is a separate switch,
     * and turning it on is an explicit request to translate images: a paused
     * text path must not silently swallow it. Reported from a device, where
     * pausing text translation left manga mode recognising 18 lines and
     * rendering none of them.
     *
     * Decided per element rather than per batch. A batch mixing both sources
     * does not arise today, but dropping half of one silently is the kind of
     * bug that takes a day to find.
     */
    private fun isPausedSource(element: TextElement): Boolean =
        element.sourceType == TextSourceType.ACCESSIBILITY

    private suspend fun onUpserted(elements: List<TextElement>) {
        if (elements.isEmpty()) return
        val pair = languages ?: return
        val readyToShow = mutableListOf<RenderedTranslation>()
        val toHide = mutableListOf<TextElementId>()

        for (element in elements) {
            if (!sensitivePolicy.isTranslatable(element)) {
                // Logged by id only — the whole point is that this text never
                // reaches diagnostics. Without the line there is no way to tell
                // "excluded on purpose" from "silently dropped".
                logger.debug(TAG, "excluded by privacy policy: ${element.id.value}")
                if (tracked.remove(element.id)?.also { it.job?.cancel() } != null) {
                    toHide += element.id
                }
                continue
            }

            val existing = tracked[element.id]
            val key = TranslationCacheKey.of(element.text, pair.forElement(element), translator.id)

            if (existing != null && existing.key == key) {
                // Same text, same languages — a scroll or re-layout, not new
                // content. Move the overlay instead of re-translating.
                existing.element = element
                existing.translatedText?.let { readyToShow += existing.render(it) }
                continue
            }

            existing?.job?.cancel()
            val entry = Tracked(element = element, key = key)
            tracked[element.id] = entry
            entry.job = launchTranslation(element, pair, key)
        }

        if (toHide.isNotEmpty()) _renderUpdates.emit(RenderUpdate.Hide(toHide))
        if (readyToShow.isNotEmpty()) _renderUpdates.emit(RenderUpdate.Show(readyToShow))
    }

    private suspend fun onRemoved(ids: List<TextElementId>) {
        val removed = mutableListOf<TextElementId>()
        for (id in ids) {
            val entry = tracked.remove(id) ?: continue
            entry.job?.cancel()
            removed += id
        }
        if (removed.isNotEmpty()) _renderUpdates.emit(RenderUpdate.Hide(removed))
    }

    /**
     * Drops what one source produced, leaving the other source's work alone.
     *
     * Scoped rather than total because manga mode passes the screen back and
     * forth between the two paths — one stands down on a page the other can
     * read — so a clear is now a routine event rather than a session ending.
     * `ClearAll` stays for [stop], which really does end everything.
     */
    private suspend fun onCleared(sourceType: TextSourceType) {
        val removed = mutableListOf<TextElementId>()
        val iterator = tracked.entries.iterator()
        while (iterator.hasNext()) {
            val (id, entry) = iterator.next()
            if (entry.element.sourceType != sourceType) continue
            entry.job?.cancel()
            iterator.remove()
            removed += id
        }
        if (removed.isNotEmpty()) _renderUpdates.emit(RenderUpdate.Hide(removed))
    }

    private suspend fun onTranslated(message: Message.Translated) {
        val entry = tracked[message.elementId] ?: return

        // Staleness is decided by the key alone. The key covers source text and
        // language pair, so changed content always invalidates an older result.
        //
        // Revision must NOT participate: scrolling bumps an element's revision
        // while its text is unchanged, and the in-flight translation is
        // deliberately left running. Comparing revisions here would discard
        // that result, so text would never appear while the user keeps
        // scrolling — exactly when they are waiting for it.
        if (entry.key != message.key) {
            logger.debug(TAG, "dropped stale result for ${message.elementId.value}")
            return
        }

        entry.translatedText = message.translatedText
        entry.job = null
        logger.debug(TAG, "translated ${message.elementId.value}")
        _renderUpdates.emit(RenderUpdate.Show(listOf(entry.render(message.translatedText))))
    }

    private fun onFailed(message: Message.Failed) {
        val entry = tracked[message.elementId] ?: return
        if (entry.key != message.key) return
        entry.job = null
        // One element failing must not take down the pipeline, so runtime state
        // is left alone — that text simply stays untranslated.
        logger.warn(TAG, "translation failed for ${message.elementId.value}: ${message.error}")
    }

    private suspend fun onSettingsChanged(settings: BabelSettings) {
        val resolved = resolveLanguages(settings)
        if (resolved == languages) return

        languages = resolved
        // Every existing translation was produced for the old language pair.
        val stale = tracked.values.toList()
        stale.forEach { it.job?.cancel() }
        tracked.clear()
        _renderUpdates.emit(RenderUpdate.ClearAll)

        for (entry in stale) {
            val element = entry.element
            if (!sensitivePolicy.isTranslatable(element)) continue
            val key = TranslationCacheKey.of(element.text, resolved, translator.id)
            val fresh = Tracked(element = element, key = key)
            tracked[element.id] = fresh
            fresh.job = launchTranslation(element, resolved, key)
        }
    }

    private fun cancelInFlight() {
        tracked.values.forEach {
            it.job?.cancel()
            it.job = null
        }
    }

    // ------------------------------------------------------------ translation

    private fun launchTranslation(
        element: TextElement,
        pair: LanguagePair,
        key: TranslationCacheKey,
    ): Job? {
        val active = scope ?: return null
        return active.launch {
            // Timed because manga mode was reported as slow and there was no
            // figure to answer with: recognition and translation are separate
            // costs and only measurement says which one to work on. The text is
            // never logged, only how long it took (`docs/systems/privacy.md`).
            val started = System.currentTimeMillis()
            try {
                val cached = cache.get(key)
                if (cached != null) {
                    logger.debug(TAG, "cached in ${System.currentTimeMillis() - started}ms")
                    mailbox.send(Message.Translated(element.id, cached, key))
                    return@launch
                }

                permits.withPermit {
                    val waitedMs = System.currentTimeMillis() - started
                    val result = translateWithRetry(element, pair)
                    logger.debug(
                        TAG,
                        "translated in ${System.currentTimeMillis() - started}ms" +
                            " (queued ${waitedMs}ms)",
                    )
                    when (val status = result.status) {
                        TranslationStatus.Translated -> {
                            cache.put(key, result.translatedText)
                            mailbox.send(
                                Message.Translated(element.id, result.translatedText, key),
                            )
                        }

                        // Source already matches the target language; an
                        // overlay would only obscure the original.
                        TranslationStatus.Unchanged -> Unit

                        is TranslationStatus.Failed -> mailbox.send(
                            Message.Failed(element.id, key, status.error),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (unexpected: Throwable) {
                logger.error(TAG, "translation crashed for ${Redact.text(element.text)}", unexpected)
                mailbox.send(
                    Message.Failed(
                        element.id,
                        key,
                        TranslationError.Unexpected(unexpected::class.simpleName),
                    ),
                )
            }
        }
    }

    /**
     * Lets an element say what language it is in, when its source knows.
     *
     * Only where the pair is on auto-detect: a source language the user chose
     * by hand outranks anything the acquisition layer believes.
     */
    private fun LanguagePair.forElement(element: TextElement): LanguagePair =
        if (source == null && element.sourceLanguage != null) {
            copy(source = element.sourceLanguage)
        } else {
            this
        }

    private suspend fun translateWithRetry(
        element: TextElement,
        pair: LanguagePair,
    ): TranslationResult {
        var attempt = 0
        while (true) {
            val request = TranslationRequest(
                requestId = RequestId("req-${requestIds.incrementAndGet()}"),
                elementId = element.id,
                revision = element.revision,
                sourceText = element.text,
                languages = pair.forElement(element),
            )
            val result = translator.translate(request)
            val status = result.status
            if (status !is TranslationStatus.Failed) return result

            attempt++
            if (!status.error.retryable || attempt > config.maxRetries) return result
            delay(config.retryDelayMillis(attempt))
        }
    }

    private suspend fun observeSettings() {
        settingsRepository.settings.collect { mailbox.send(Message.SettingsChanged(it)) }
    }

    private fun resolveLanguages(settings: BabelSettings): LanguagePair =
        languageResolver.resolve(settings.sourceLanguageMode, settings.targetLanguageMode)

    private fun drainMailbox() {
        while (mailbox.tryReceive().isSuccess) {
            // Discard queued work from the stopped session.
        }
    }

    // ------------------------------------------------------------------ state

    private class Tracked(
        element: TextElement,
        val key: TranslationCacheKey,
    ) {
        @Volatile
        var element: TextElement = element

        @Volatile
        var translatedText: String? = null

        @Volatile
        var job: Job? = null

        fun render(text: String) = RenderedTranslation(
            elementId = element.id,
            revision = element.revision,
            text = text,
            // Straight off the element this tracks — the words that were on the
            // screen. No plumbing from the result needed; they were here first.
            originalText = element.text,
            bounds = element.bounds,
            // Carried straight through: the domain decides *what* to show, the
            // acquisition layer is the only one that ever saw the pixels.
            style = StyleHints(sourceStyle = element.style),
            sourceType = element.sourceType,
        )
    }

    private sealed interface Message {
        data class Source(val event: TextSourceEvent) : Message

        /** [key] identifies what was translated; see [onTranslated]. */
        data class Translated(
            val elementId: TextElementId,
            val translatedText: String,
            val key: TranslationCacheKey,
        ) : Message

        data class Failed(
            val elementId: TextElementId,
            val key: TranslationCacheKey,
            val error: TranslationError,
        ) : Message

        data class SettingsChanged(val settings: BabelSettings) : Message

        data object CancelInFlight : Message
    }

    private companion object {
        const val TAG = "TranslationCoordinator"
        const val RENDER_BUFFER = 64
    }
}
