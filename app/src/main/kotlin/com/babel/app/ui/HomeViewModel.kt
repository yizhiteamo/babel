package com.babel.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.babel.core.model.Capability
import com.babel.core.model.CapabilityState
import com.babel.core.model.CapabilityStatus
import com.babel.core.model.LanguageTag
import com.babel.core.model.TargetLanguageMode
import com.babel.domain.language.LanguageResolver
import com.babel.domain.runtime.CapabilityChecker
import com.babel.core.model.ApiKey
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.vision.CaptureState
import com.babel.core.model.TranslationRuntimeState
import com.babel.domain.translation.TranslationCoordinator
import com.babel.domain.vision.ScreenCaptureController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val capabilities: CapabilityState = CapabilityState(),
    val settings: BabelSettings = BabelSettings(),
    val targetLanguage: LanguageTag? = null,
    val captureState: CaptureState = CaptureState.IDLE,
    val runtimeState: TranslationRuntimeState = TranslationRuntimeState.Disabled,
) {
    /**
     * Whether pausing or resuming makes sense right now.
     *
     * Only between running and paused. Calling `start()` while the coordinator
     * is stopped would bring it up with no text source attached, which is a
     * state nothing else can produce and nothing handles.
     */
    val canTogglePause: Boolean
        get() = runtimeState == TranslationRuntimeState.Running ||
            runtimeState == TranslationRuntimeState.Paused

    val translationPaused: Boolean get() = runtimeState == TranslationRuntimeState.Paused

    val accessibilityGranted: Boolean
        get() = capabilities[Capability.ACCESSIBILITY_SERVICE] == CapabilityStatus.AVAILABLE

    val overlayGranted: Boolean
        get() = capabilities[Capability.OVERLAY_WINDOW] == CapabilityStatus.AVAILABLE

    val readyToTranslate: Boolean get() = accessibilityGranted && overlayGranted
}

/**
 * Observes state, and lets the user pause.
 *
 * Translation still *starts and stops* with the accessibility service, because
 * that is the only thing whose lifetime matches — a ViewModel dies when the
 * user leaves the app, which is exactly when translation needs to keep running.
 * Pausing is different: it is a deliberate act with an obvious undo, and the
 * coordinator has supported it all along (`TranslationCoordinator.pause`).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val capabilityChecker: CapabilityChecker,
    private val settingsRepository: SettingsRepository,
    private val languageResolver: LanguageResolver,
    private val screenCapture: ScreenCaptureController,
    private val coordinator: TranslationCoordinator,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        capabilityChecker.state,
        settingsRepository.settings,
        screenCapture.state,
        coordinator.runtimeState,
    ) { capabilities, settings, captureState, runtimeState ->
        HomeUiState(
            capabilities = capabilities,
            settings = settings,
            targetLanguage = languageResolver.resolve(
                settings.sourceLanguageMode,
                settings.targetLanguageMode,
            ).target,
            captureState = captureState,
            runtimeState = runtimeState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /** Permissions change outside the app, so re-read them on return. */
    fun refreshCapabilities() = capabilityChecker.refresh()

    /**
     * No consent dialog stands in front of this any more, so it is a direct
     * call rather than something the Activity has to launch (ADR 009).
     */
    /**
     * Stops translating new content while leaving what is already drawn.
     *
     * That is the coordinator's own definition of pause, and the label says so:
     * calling this "off" would promise a clear screen that does not happen
     * until the content changes.
     */
    fun pauseTranslation() = coordinator.pause()

    fun resumeTranslation() = coordinator.start()

    fun startMangaMode() = screenCapture.start()

    fun stopMangaMode() = screenCapture.stop()

    fun selectTargetLanguage(tag: LanguageTag?) {
        viewModelScope.launch {
            settingsRepository.setTargetLanguageMode(
                if (tag == null) {
                    TargetLanguageMode.FollowSystem
                } else {
                    TargetLanguageMode.Manual(tag)
                },
            )
        }
    }

    /**
     * Turns remote translation on, and records where to reach it.
     *
     * Both halves are written together because either alone is meaningless: a
     * configured endpoint nobody selected sends nothing, and a selected
     * provider with no endpoint fails every request
     * (`docs/decisions/010-remote-translation.md`).
     */
    fun enableRemoteTranslation(settings: RemoteProviderSettings) {
        viewModelScope.launch {
            // Taken as given: the dialog resolved which service, and what a
            // blank key means, against what was already stored. Re-deriving any
            // of that here is how the two ended up disagreeing once already.
            settingsRepository.setRemoteProvider(settings)
            settingsRepository.setProvider(RemoteProviderSettings.PROVIDER)
        }
    }

    /**
     * Stops sending anything, without forgetting the configuration.
     *
     * Clearing the endpoint too would make turning it back on a retyping
     * exercise. Deselecting the provider is what stops the sending, and it is
     * the half the user is actually asking about.
     */
    fun disableRemoteTranslation() {
        viewModelScope.launch { settingsRepository.setProvider(null) }
    }

    fun supportedLanguages(): List<LanguageTag> = languageResolver.supportedLanguages()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
