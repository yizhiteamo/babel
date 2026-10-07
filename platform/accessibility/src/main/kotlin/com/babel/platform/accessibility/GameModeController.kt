package com.babel.platform.accessibility

import com.babel.core.common.BabelLogger
import com.babel.domain.game.GameCaptureController
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.vision.CaptureState
import com.babel.platform.screen.ScreenFrameSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds whether game mode is on.
 *
 * Manga mode's controller, with game mode's requirement: it needs pictures of
 * one window, which Android 14 brought (`docs/features/v3-game-translation.md`).
 * The same rule about writing applies — only the user's own start and stop are
 * remembered; a mode taken down because the service went away writes nothing,
 * and comes back on its own when the service does.
 */
class GameModeController(
    private val frames: ScreenFrameSource,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val logger: BabelLogger,
) : GameCaptureController {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    override fun start() {
        if (activate()) remember(on = true)
    }

    override fun stop() {
        if (_state.value == CaptureState.IDLE) return
        _state.value = CaptureState.IDLE
        logger.info(TAG, "game mode off")
        remember(on = false)
    }

    private fun activate(): Boolean {
        if (!frames.supportsWindowFrames) {
            logger.info(TAG, "game mode needs Android 14")
            _state.value = CaptureState.UNSUPPORTED
            return false
        }
        if (!frames.isAvailable) {
            _state.value = CaptureState.UNAVAILABLE
            return false
        }
        _state.value = CaptureState.ACTIVE
        logger.info(TAG, "game mode on")
        return true
    }

    private fun remember(on: Boolean) {
        scope.launch { settings.setGameMode(on) }
    }

    internal fun onServiceAvailabilityChanged() {
        when {
            frames.isAvailable && _state.value == CaptureState.UNAVAILABLE -> _state.value = CaptureState.IDLE
            !frames.isAvailable && _state.value == CaptureState.ACTIVE -> _state.value = CaptureState.UNAVAILABLE
        }
        if (frames.isAvailable && _state.value == CaptureState.IDLE) {
            scope.launch { if (settings.settings.first().gameMode) activate() }
        }
    }

    private companion object {
        const val TAG = "GameMode"
    }
}
