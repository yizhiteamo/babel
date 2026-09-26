package com.babel.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.babel.core.model.ApiKey
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import org.junit.rules.TemporaryFolder

/**
 * Uses real file-backed DataStore rather than a fake, because the thing worth
 * testing here is the round trip through storage — mode and language are
 * separate keys, and a sealed type has to survive being split and rejoined.
 *
 * `runBlocking`, not `runTest`: DataStore performs real IO, so virtual time
 * would only hide the work.
 */
class DataStoreSettingsRepositoryTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private fun <T> withRepository(block: suspend (DataStoreSettingsRepository) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return try {
            val file = File(temporaryFolder.root, "settings.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            runBlocking { block(DataStoreSettingsRepository(store)) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `defaults are auto-detect and follow-system`() = withRepository { repo ->
        val settings = repo.settings.first()

        assertEquals(SourceLanguageMode.AutoDetect, settings.sourceLanguageMode)
        assertEquals(TargetLanguageMode.FollowSystem, settings.targetLanguageMode)
        assertNull(settings.provider)
        assertEquals(false, settings.translationPaused)
    }

    @Test
    fun `manual source language survives a round trip`() = withRepository { repo ->
        repo.setSourceLanguageMode(SourceLanguageMode.Manual(LanguageTag("ja")))

        assertEquals(
            SourceLanguageMode.Manual(LanguageTag("ja")),
            repo.settings.first().sourceLanguageMode,
        )
    }

    @Test
    fun `manual target language survives a round trip`() = withRepository { repo ->
        repo.setTargetLanguageMode(TargetLanguageMode.Manual(LanguageTag("zh-Hans")))

        assertEquals(
            TargetLanguageMode.Manual(LanguageTag("zh-Hans")),
            repo.settings.first().targetLanguageMode,
        )
    }

    @Test
    fun `switching back to auto clears the stored language`() = withRepository { repo ->
        repo.setSourceLanguageMode(SourceLanguageMode.Manual(LanguageTag("ja")))
        repo.setSourceLanguageMode(SourceLanguageMode.AutoDetect)

        assertEquals(SourceLanguageMode.AutoDetect, repo.settings.first().sourceLanguageMode)
    }

    @Test
    fun `switching back to follow-system clears the stored language`() = withRepository { repo ->
        repo.setTargetLanguageMode(TargetLanguageMode.Manual(LanguageTag("ja")))
        repo.setTargetLanguageMode(TargetLanguageMode.FollowSystem)

        assertEquals(TargetLanguageMode.FollowSystem, repo.settings.first().targetLanguageMode)
    }

    @Test
    fun `provider can be set and cleared`() = withRepository { repo ->
        repo.setProvider(ProviderId("mlkit"))
        assertEquals(ProviderId("mlkit"), repo.settings.first().provider)

        repo.setProvider(null)
        assertNull(repo.settings.first().provider)
    }

    @Test
    fun `the pause choice is persisted`() = withRepository { repo ->
        repo.setTranslationPaused(true)

        assertEquals(true, repo.settings.first().translationPaused)
    }

    @Test
    fun `settings flow emits after a change`() = withRepository { repo ->
        repo.setTranslationPaused(true)
        repo.setTargetLanguageMode(TargetLanguageMode.Manual(LanguageTag("de")))

        val settings = repo.settings.first()
        assertEquals(true, settings.translationPaused)
        assertEquals(TargetLanguageMode.Manual(LanguageTag("de")), settings.targetLanguageMode)
    }

    @Test
    fun `each service keeps its own key`() = withRepository { repo ->
        repo.setRemoteProvider(
            RemoteProviderSettings(
                endpoint = "https://example.invalid/v1/chat/completions",
                model = "some-model",
                chatKey = ApiKey("chat-secret"),
            ),
        )
        // Configuring the other service used to overwrite the first one's key,
        // and switching back sent a DeepL credential to a chat endpoint.
        repo.setRemoteProvider(
            repo.settings.first().remote
                .copy(service = RemoteService.DEEPL)
                .withKeyFor(RemoteService.DEEPL, ApiKey("deepl-secret:fx")),
        )

        val stored = repo.settings.first().remote
        assertEquals("chat-secret", stored.chatKey.value)
        assertEquals("deepl-secret:fx", stored.deepLKey.value)
    }

    @Test
    fun `a key stored before the split belongs to the service it was stored for`() {
        val file = File(temporaryFolder.root, "legacy.preferences_pb")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            runBlocking {
                // What an older build wrote: one key, no service of its own.
                store.edit { prefs ->
                    prefs[stringPreferencesKey("remote_service")] = "DEEPL"
                    prefs[stringPreferencesKey("remote_api_key")] = "old-deepl:fx"
                }

                val remote = DataStoreSettingsRepository(store).settings.first().remote
                assertEquals("old-deepl:fx", remote.deepLKey.value)
                // And emphatically not to the other one, which is the whole
                // point: a DeepL key sent to a chat endpoint answers 401.
                assertEquals("", remote.chatKey.value)
            }
        } finally {
            scope.cancel()
        }
    }
}
