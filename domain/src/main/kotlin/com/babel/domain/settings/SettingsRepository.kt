package com.babel.domain.settings

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
    /** Translation resumes automatically when its capabilities are available. */
    val autoStart: Boolean = false,
)

interface SettingsRepository {
    val settings: Flow<BabelSettings>

    suspend fun setSourceLanguageMode(mode: SourceLanguageMode)

    suspend fun setTargetLanguageMode(mode: TargetLanguageMode)

    suspend fun setProvider(provider: ProviderId?)

    suspend fun setAutoStart(enabled: Boolean)
}
