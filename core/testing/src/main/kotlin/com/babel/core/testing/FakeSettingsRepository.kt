package com.babel.core.testing

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.domain.language.SystemLocaleProvider
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSettingsRepository(
    initial: BabelSettings = BabelSettings(),
) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<BabelSettings> = state

    val current: BabelSettings get() = state.value

    override suspend fun setSourceLanguageMode(mode: SourceLanguageMode) {
        state.value = state.value.copy(sourceLanguageMode = mode)
    }

    override suspend fun setTargetLanguageMode(mode: TargetLanguageMode) {
        state.value = state.value.copy(targetLanguageMode = mode)
    }

    override suspend fun setProvider(provider: ProviderId?) {
        state.value = state.value.copy(provider = provider)
    }

    override suspend fun setRemoteProvider(settings: RemoteProviderSettings) {
        state.value = state.value.copy(remote = settings)
    }

    override suspend fun setAutoStart(enabled: Boolean) {
        state.value = state.value.copy(autoStart = enabled)
    }

    /** How many times manga mode was written, so a test can prove it was not. */
    var mangaModeWrites = 0
        private set

    override suspend fun setMangaMode(enabled: Boolean) {
        mangaModeWrites += 1
        state.value = state.value.copy(mangaMode = enabled)
    }
}

/**
 * The system language is an input to language resolution, so tests need to move
 * it around. Changing [tag] simulates the user switching device language.
 */
class FakeSystemLocaleProvider(
    var tag: LanguageTag = LanguageTag("en"),
) : SystemLocaleProvider {
    override fun current(): LanguageTag = tag
}
