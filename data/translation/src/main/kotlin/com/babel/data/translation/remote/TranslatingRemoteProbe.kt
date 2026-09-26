package com.babel.data.translation.remote

import com.babel.core.common.BabelLogger
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationStatus
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.translation.ProbeResult
import com.babel.domain.translation.RemoteProbe
import com.babel.domain.translation.probeResultOf
import com.babel.domain.translation.Translator
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Checks settings by using them.
 *
 * Deliberately the **real** translator rather than a bare HTTP call. The thing
 * being checked is whether this configuration can translate, and a
 * hand-written probe request would be a second implementation of the auth
 * header, the payload and the path — three things that have each been wrong
 * here at least once. A copy that passes while the real one fails is worse than
 * no check at all.
 *
 * The draft settings are handed over as a one-shot repository, because that is
 * how a translator asks: it reads the current settings for every request. That
 * is also what lets this check something the user has not saved yet.
 */
@Singleton
class TranslatingRemoteProbe @Inject constructor(
    private val logger: BabelLogger = BabelLogger.NoOp,
) : RemoteProbe {

    override suspend fun check(settings: RemoteProviderSettings): ProbeResult {
        if (!settings.isConfigured) return ProbeResult.NotConfigured

        val repository = Fixed(settings)
        val translator: Translator = when (settings.service) {
            RemoteService.CHAT -> ChatTranslator(repository, logger)
            RemoteService.DEEPL -> DeepLTranslator(repository, logger)
        }

        val result = translator.translate(
            TranslationRequest(
                requestId = RequestId("probe"),
                elementId = TextElementId("probe"),
                revision = Revision(0),
                // Short, harmless, and nothing off anybody's screen: a probe
                // must not be a reason for screen text to leave the device
                // before the user has finished deciding whether to allow it.
                sourceText = PROBE_TEXT,
                languages = LanguagePair(source = LanguageTag("en"), target = LanguageTag("zh")),
            ),
        )

        return when (val status = result.status) {
            // Unchanged counts: the service answered, and answering is the
            // question. A translator handing `hello` back is configured.
            is TranslationStatus.Translated, TranslationStatus.Unchanged -> ProbeResult.Ok
            is TranslationStatus.Failed -> status.error.asProbeResult()
        }
    }

    private fun TranslationError.asProbeResult(): ProbeResult = probeResultOf(this)

    /** The draft under test, answered to whoever asks for settings. */
    private class Fixed(private val remote: RemoteProviderSettings) : SettingsRepository {
        override val settings: Flow<BabelSettings> = flowOf(BabelSettings(remote = remote))
        override suspend fun setSourceLanguageMode(mode: com.babel.core.model.SourceLanguageMode) = Unit
        override suspend fun setTargetLanguageMode(mode: com.babel.core.model.TargetLanguageMode) = Unit
        override suspend fun setProvider(provider: com.babel.core.model.ProviderId?) = Unit
        override suspend fun setRemoteProvider(settings: RemoteProviderSettings) = Unit
        override suspend fun setTranslationPaused(paused: Boolean) = Unit
        override suspend fun setMangaMode(enabled: Boolean) = Unit
    }

    private companion object {
        const val PROBE_TEXT = "hello"
    }
}
