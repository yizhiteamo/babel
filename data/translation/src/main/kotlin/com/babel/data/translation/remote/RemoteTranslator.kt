package com.babel.data.translation.remote

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.domain.settings.RemoteService
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.translation.Translator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Picks which remote service the text goes to.
 *
 * `RoutingTranslator` stays a two-way question — on-device or not — because
 * that is the one the privacy gate asks, and it should not grow a third answer
 * every time a service is added. This sits behind its remote arm and answers
 * the narrower question of *which* remote.
 *
 * ## Why [id] moves
 *
 * Exactly the reason `RoutingTranslator` gives for its own: `TranslationCacheKey`
 * carries the provider, so a translation DeepL produced must never be served for
 * a request the chat route would have answered. Reporting the live service's id
 * is what keeps a cache correct across a switch.
 *
 * ## Why two shapes rather than one configurable one
 *
 * ADR 010 chose an OpenAI-compatible chat endpoint precisely so one shape would
 * cover every provider, and that reasoning held until the measurements did not:
 * a chat model breaks down on short input, which is what a comic balloon is
 * (`docs/milestones/v2.md`). A translation API has no instruction to disobey.
 * The two are different protocols, not two vendors of one, so they are two
 * implementations.
 */
class RemoteTranslator(
    private val chat: Translator,
    private val deepL: Translator,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) : Translator {

    @Volatile
    private var service: RemoteService = RemoteService.CHAT

    init {
        settingsRepository.settings
            .onEach { settings -> service = settings.remote.service }
            .launchIn(scope)
    }

    private val current: Translator
        get() = when (service) {
            RemoteService.CHAT -> chat
            RemoteService.DEEPL -> deepL
        }

    override val id: ProviderId get() = current.id

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean =
        current.supports(source, target)

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        current.translate(request)
}
