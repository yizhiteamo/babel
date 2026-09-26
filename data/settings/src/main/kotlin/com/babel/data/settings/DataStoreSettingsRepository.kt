package com.babel.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.babel.core.model.ApiKey
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
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

    /**
     * The key is stored beside the rest, in the app's private DataStore.
     *
     * Worth being plain about what that is and is not: it is readable by this
     * app and by anyone with the device unlocked and root, and it is not
     * hardware-backed. It is the user's own credential for a service they chose,
     * which is the same posture a terminal's config file takes. What it must
     * never do is travel — it goes to the configured endpoint and nowhere else,
     * and [ApiKey] cannot print itself into a log.
     */
    override suspend fun setRemoteProvider(settings: RemoteProviderSettings) {
        dataStore.edit { prefs ->
            prefs[Keys.REMOTE_SERVICE] = settings.service.name
            prefs[Keys.REMOTE_ENDPOINT] = settings.endpoint
            prefs[Keys.REMOTE_MODEL] = settings.model
            prefs[Keys.REMOTE_CHAT_KEY] = settings.chatKey.value
            prefs[Keys.REMOTE_DEEPL_KEY] = settings.deepLKey.value
            // The single shared key this replaced. Cleared so that a build
            // which still reads it cannot serve one service's credential to
            // the other, which is the defect the split exists to fix.
            prefs.remove(Keys.REMOTE_KEY)
        }
    }

    override suspend fun setAutoStart(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.AUTO_START] = enabled }
    }

    override suspend fun setMangaMode(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.MANGA_MODE] = enabled }
    }

    private fun toSettings(prefs: Preferences): BabelSettings = BabelSettings(
        sourceLanguageMode = readSourceMode(prefs),
        targetLanguageMode = readTargetMode(prefs),
        provider = prefs[Keys.PROVIDER]?.takeIf { it.isNotBlank() }?.let(::ProviderId),
        remote = RemoteProviderSettings(
            service = readRemoteService(prefs),
            endpoint = prefs[Keys.REMOTE_ENDPOINT].orEmpty(),
            model = prefs[Keys.REMOTE_MODEL].orEmpty(),
            chatKey = readKey(prefs, Keys.REMOTE_CHAT_KEY, RemoteService.CHAT),
            deepLKey = readKey(prefs, Keys.REMOTE_DEEPL_KEY, RemoteService.DEEPL),
        ),
        autoStart = prefs[Keys.AUTO_START] ?: false,
        mangaMode = prefs[Keys.MANGA_MODE] ?: false,
    )

    /**
     * A service's own key, or the shared one it used to have.
     *
     * Settings written before the split hold one key under [Keys.REMOTE_KEY],
     * and it belongs to whichever service was selected at the time. Handing it
     * to the other one would send a DeepL key to a chat endpoint, which is
     * exactly the failure the split was made to stop.
     */
    private fun readKey(
        prefs: Preferences,
        key: Preferences.Key<String>,
        owner: RemoteService,
    ): ApiKey {
        prefs[key]?.takeIf { it.isNotBlank() }?.let { return ApiKey(it) }
        val shared = prefs[Keys.REMOTE_KEY].orEmpty()
        return if (shared.isNotBlank() && readRemoteService(prefs) == owner) {
            ApiKey(shared)
        } else {
            ApiKey("")
        }
    }

    /**
     * Falls back to the chat service, which is what every stored configuration
     * predating this key is. An unrecognised name does the same rather than
     * throwing: a settings file from a newer build must not crash an older one.
     */
    private fun readRemoteService(prefs: Preferences): RemoteService {
        val stored = prefs[Keys.REMOTE_SERVICE] ?: return RemoteService.CHAT
        return RemoteService.entries.firstOrNull { it.name == stored } ?: RemoteService.CHAT
    }

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
        val REMOTE_SERVICE = stringPreferencesKey("remote_service")
        val REMOTE_ENDPOINT = stringPreferencesKey("remote_endpoint")
        val REMOTE_MODEL = stringPreferencesKey("remote_model")
        /** Superseded by the per-service keys; still read once, for migration. */
        val REMOTE_KEY = stringPreferencesKey("remote_api_key")
        val REMOTE_CHAT_KEY = stringPreferencesKey("remote_chat_key")
        val REMOTE_DEEPL_KEY = stringPreferencesKey("remote_deepl_key")
        val AUTO_START = booleanPreferencesKey("auto_start")
        val MANGA_MODE = booleanPreferencesKey("manga_mode")
    }

    private companion object {
        const val MODE_AUTO = "auto"
        const val MODE_SYSTEM = "system"
        const val MODE_MANUAL = "manual"
    }
}
