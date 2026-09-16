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
    val endpoint: String = "",
    val model: String = "",
    val apiKey: ApiKey = ApiKey(""),
) {
    /**
     * Enough to reach somewhere. Deliberately **not** including the key.
     *
     * A hosted service needs one and will answer 401 without it, which is
     * visible and fixable. A model running on the user's own machine needs
     * none — and that is the one configuration where the text never reaches
     * the internet at all, so requiring a credential for it turned the
     * privacy-preserving option into the unreachable one (ADR 010).
     */
    val isConfigured: Boolean
        get() = endpoint.isNotBlank() && model.isNotBlank()

    companion object {
        /** The id this provider reports, which is also what cache keys carry. */
        val PROVIDER: ProviderId = ProviderId("remote-chat")
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
