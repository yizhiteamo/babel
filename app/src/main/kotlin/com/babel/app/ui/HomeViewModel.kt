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
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.vision.CaptureState
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
) {
    val accessibilityGranted: Boolean
        get() = capabilities[Capability.ACCESSIBILITY_SERVICE] == CapabilityStatus.AVAILABLE

    val overlayGranted: Boolean
        get() = capabilities[Capability.OVERLAY_WINDOW] == CapabilityStatus.AVAILABLE

    val readyToTranslate: Boolean get() = accessibilityGranted && overlayGranted
}

/**
 * Observes state; it does not drive the pipeline.
 *
 * Translation starts and stops with the accessibility service, because that is
 * the only thing whose lifetime matches — a ViewModel dies when the user leaves
 * the app, which is exactly when translation needs to keep running.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val capabilityChecker: CapabilityChecker,
    private val settingsRepository: SettingsRepository,
    private val languageResolver: LanguageResolver,
    screenCapture: ScreenCaptureController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(
        capabilityChecker.state,
        settingsRepository.settings,
        screenCapture.state,
    ) { capabilities, settings, captureState ->
        HomeUiState(
            capabilities = capabilities,
            settings = settings,
            targetLanguage = languageResolver.resolve(
                settings.sourceLanguageMode,
                settings.targetLanguageMode,
            ).target,
            captureState = captureState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(),
    )

    /** Permissions change outside the app, so re-read them on return. */
    fun refreshCapabilities() = capabilityChecker.refresh()

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

    fun supportedLanguages(): List<LanguageTag> = languageResolver.supportedLanguages()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
