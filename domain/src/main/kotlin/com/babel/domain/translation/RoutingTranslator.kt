package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.domain.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Sends each request to whichever translator the user has chosen.
 *
 * Remote translation is off unless it is both selected and configured, so the
 * default route is the on-device one and nothing leaves the device until
 * somebody says so (`docs/decisions/010-remote-translation.md`).
 *
 * ## The two routes are not wrapped the same way
 *
 * The on-device route is wrapped in [FragmentingTranslator], which cuts a line
 * at its ellipses. That was measured as a clear win for ML Kit — it recovers
 * whole sentences the engine otherwise drops — but it is a compensation for a
 * weak engine, not an improvement in itself. Measured against a stronger model,
 * feeding it *whole* balloons is what helps and splitting is what hurts
 * (`docs/milestones/v2.md`). So the remote route gets the text as it stands.
 *
 * ## Why [id] moves
 *
 * `TranslationCacheKey` includes the provider, so a cached translation from one
 * engine is never served for the other. Reporting the live route's id is what
 * makes that true when the user switches.
 */
class RoutingTranslator(
    private val local: Translator,
    private val remote: Translator,
    settingsRepository: SettingsRepository,
    scope: CoroutineScope,
) : Translator {

    @Volatile
    private var useRemote: Boolean = false

    init {
        settingsRepository.settings
            .onEach { settings -> useRemote = settings.usesRemoteTranslation }
            .launchIn(scope)
    }

    private val current: Translator get() = if (useRemote) remote else local

    override val id: ProviderId get() = current.id

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean =
        current.supports(source, target)

    override suspend fun translate(request: TranslationRequest): TranslationResult =
        current.translate(request)
}
