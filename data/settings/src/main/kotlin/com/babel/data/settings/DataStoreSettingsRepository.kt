package com.babel.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.SettingsRepository
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The one place storage keys exist. Callers see [BabelSettings] only
 * (`docs/systems/settings.md`).
 *
 * Mode and language are stored as separate keys rather than one encoded string,
 * so a corrupt or partially written value degrades to the default for that
 * field instead of failing to parse the whole record.
 */
class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val settings: Flow<BabelSettings> = dataStore.data
        // A read failure must not kill the pipeline; fall back to defaults.
        .catch { cause ->
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map(::toSettings)

    override suspend fun setSourceLanguageMode(mode: SourceLanguageMode) {
        dataStore.edit { prefs ->
            when (mode) {
                SourceLanguageMode.AutoDetect -> {
                    prefs[Keys.SOURCE_MODE] = MODE_AUTO
                    prefs.remove(Keys.SOURCE_LANGUAGE)
                }

                is SourceLanguageMode.Manual -> {
                    prefs[Keys.SOURCE_MODE] = MODE_MANUAL
                    prefs[Keys.SOURCE_LANGUAGE] = mode.language.value
                }
            }
        }
    }

    override suspend fun setTargetLanguageMode(mode: TargetLanguageMode) {
        dataStore.edit { prefs ->
            when (mode) {
                TargetLanguageMode.FollowSystem -> {
                    prefs[Keys.TARGET_MODE] = MODE_SYSTEM
                    prefs.remove(Keys.TARGET_LANGUAGE)
                }

                is TargetLanguageMode.Manual -> {
                    prefs[Keys.TARGET_MODE] = MODE_MANUAL
                    prefs[Keys.TARGET_LANGUAGE] = mode.language.value
                }
            }
        }
    }

    override suspend fun setProvider(provider: ProviderId?) {
        dataStore.edit { prefs ->
            if (provider == null) prefs.remove(Keys.PROVIDER) else prefs[Keys.PROVIDER] = provider.value
        }
    }

    override suspend fun setAutoStart(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_START] = enabled }
    }

    private fun toSettings(prefs: Preferences): BabelSettings = BabelSettings(
        sourceLanguageMode = readSourceMode(prefs),
        targetLanguageMode = readTargetMode(prefs),
        provider = prefs[Keys.PROVIDER]?.takeIf { it.isNotBlank() }?.let(::ProviderId),
        autoStart = prefs[Keys.AUTO_START] ?: false,
    )

    private fun readSourceMode(prefs: Preferences): SourceLanguageMode {
        if (prefs[Keys.SOURCE_MODE] != MODE_MANUAL) return SourceLanguageMode.AutoDetect
        val language = prefs[Keys.SOURCE_LANGUAGE]?.takeIf { it.isNotBlank() }
            ?: return SourceLanguageMode.AutoDetect
        return SourceLanguageMode.Manual(LanguageTag(language))
    }

    private fun readTargetMode(prefs: Preferences): TargetLanguageMode {
        if (prefs[Keys.TARGET_MODE] != MODE_MANUAL) return TargetLanguageMode.FollowSystem
        val language = prefs[Keys.TARGET_LANGUAGE]?.takeIf { it.isNotBlank() }
            ?: return TargetLanguageMode.FollowSystem
        return TargetLanguageMode.Manual(LanguageTag(language))
    }

    private object Keys {
        val SOURCE_MODE = stringPreferencesKey("source_language_mode")
        val SOURCE_LANGUAGE = stringPreferencesKey("source_language")
        val TARGET_MODE = stringPreferencesKey("target_language_mode")
        val TARGET_LANGUAGE = stringPreferencesKey("target_language")
        val PROVIDER = stringPreferencesKey("provider")
        val AUTO_START = booleanPreferencesKey("auto_start")
    }

    private companion object {
        const val MODE_AUTO = "auto"
        const val MODE_SYSTEM = "system"
        const val MODE_MANUAL = "manual"
    }
}
