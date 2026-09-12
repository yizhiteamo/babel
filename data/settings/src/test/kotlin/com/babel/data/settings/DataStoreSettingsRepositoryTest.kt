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
        assertEquals(false, settings.autoStart)
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
    fun `auto start is persisted`() = withRepository { repo ->
        repo.setAutoStart(true)

        assertEquals(true, repo.settings.first().autoStart)
    }

    @Test
    fun `settings flow emits after a change`() = withRepository { repo ->
        repo.setAutoStart(true)
        repo.setTargetLanguageMode(TargetLanguageMode.Manual(LanguageTag("de")))

        val settings = repo.settings.first()
        assertEquals(true, settings.autoStart)
        assertEquals(TargetLanguageMode.Manual(LanguageTag("de")), settings.targetLanguageMode)
    }
}
