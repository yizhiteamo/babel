package com.babel.domain.settings

import com.babel.core.model.ApiKey
import com.babel.core.model.ProviderId
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import kotlinx.coroutines.flow.Flow

/**
 * Project-owned settings model. UI observes this; it never sees storage keys or
 * DataStore types (`docs/systems/settings.md`).
 */
data class BabelSettings(
    val sourceLanguageMode: SourceLanguageMode = SourceLanguageMode.AutoDetect,
    val targetLanguageMode: TargetLanguageMode = TargetLanguageMode.FollowSystem,
    val provider: ProviderId? = null,
    val remote: RemoteProviderSettings = RemoteProviderSettings(),
    /** Translation resumes automatically when its capabilities are available. */
    val autoStart: Boolean = false,
) {
    /**
     * Whether text is allowed to leave the device.
     *
     * Both halves are required, deliberately: choosing the provider is the user
     * saying yes, and a configured endpoint is what makes that yes actionable.
     * Neither alone sends anything anywhere. The key is not one of the halves —
     * see [RemoteProviderSettings.isConfigured].
     */
    val usesRemoteTranslation: Boolean
        get() = provider == RemoteProviderSettings.PROVIDER && remote.isConfigured
}

/**
 * Where to reach a remote translator, when the user has asked for one.
 *
 * Expressed as an OpenAI-compatible chat endpoint rather than as a named
 * service. That one shape covers the hosted model this was asked for and the
 * several providers that copy its API, and it lets a user point Babel at
 * something running on their own machine. Naming a single vendor here would buy
 * nothing and exclude all of that.
 */
data class RemoteProviderSettings(
    val service: RemoteService = RemoteService.CHAT,
    val endpoint: String = "",
    val model: String = "",
    /**
     * The chat endpoint's credential, kept apart from DeepL's.
     *
     * One field used to serve both, and switching service overwrote whichever
     * key was already there: entering a DeepL key destroyed the chat one, and
     * switching back sent the DeepL key to the chat endpoint, which answers
     * 401. Two services cannot be configured at once if they share one box.
     */
    val chatKey: ApiKey = ApiKey(""),
    /** DeepL's credential. See [chatKey] for why it is its own field. */
    val deepLKey: ApiKey = ApiKey(""),
) {

    /** Whichever credential the selected service uses. */
    val apiKey: ApiKey
        get() = when (service) {
            RemoteService.CHAT -> chatKey
            RemoteService.DEEPL -> deepLKey
        }

    /** The same settings with [key] stored against [service]. */
    fun withKeyFor(service: RemoteService, key: ApiKey): RemoteProviderSettings = when (service) {
        RemoteService.CHAT -> copy(chatKey = key)
        RemoteService.DEEPL -> copy(deepLKey = key)
    }

    /**
     * Enough to reach somewhere — which is a different question per service.
     *
     * [RemoteService.CHAT] needs an address and a model name, and deliberately
     * **not** a key: a hosted service answers 401 without one, which is visible
     * and fixable, while a model on the user's own machine wants none at all.
     * Requiring a credential there turned the one configuration where the text
     * never reaches the internet into the only one the app refused (ADR 010).
     *
     * [RemoteService.DEEPL] is the mirror image: the key is all there is. The
     * address follows from the key and there is no model to choose.
     *
     * The UI asks this rather than restating it, so the button a user presses
     * and the gate that routes their text cannot disagree.
     */
    val isConfigured: Boolean
        get() = when (service) {
            RemoteService.CHAT -> endpoint.isNotBlank() && model.isNotBlank()
            RemoteService.DEEPL -> deepLKey.isPresent
        }

    companion object {
        /**
         * Marks the remote route as selected, whichever service is behind it.
         *
         * Not the same thing as the id a translator reports: cache keys carry
         * the engine that produced a translation, and the two services must
         * never share one ([RemoteService.providerId]).
         */
        val PROVIDER: ProviderId = ProviderId("remote-chat")
    }
}

/**
 * Which kind of service is behind the remote route.
 *
 * Two shapes, because they are genuinely different protocols rather than two
 * vendors of one. A chat model is told what the text is and asked to translate
 * it; a translation API is handed the text and a target language. ADR 010
 * originally carried only the first, on the grounds that one shape covers every
 * provider — which held right up until the measurements said a narrow
 * translator is what a comic balloon wants.
 */
enum class RemoteService {
    /** Any OpenAI-compatible chat endpoint, hosted or on the user's machine. */
    CHAT,

    /** DeepL's translation API, free tier included. */
    DEEPL,
    ;

    /**
     * What a translation made by this service is filed under.
     *
     * `TranslationCacheKey` carries the provider, so switching services must
     * not serve one engine's translation for the other's request.
     */
    val providerId: ProviderId
        get() = when (this) {
            CHAT -> ProviderId("remote-chat")
            DEEPL -> ProviderId("deepl")
        }
}

interface SettingsRepository {
    val settings: Flow<BabelSettings>

    suspend fun setSourceLanguageMode(mode: SourceLanguageMode)

    suspend fun setTargetLanguageMode(mode: TargetLanguageMode)

    suspend fun setProvider(provider: ProviderId?)

    suspend fun setRemoteProvider(settings: RemoteProviderSettings)

    suspend fun setAutoStart(enabled: Boolean)
}
