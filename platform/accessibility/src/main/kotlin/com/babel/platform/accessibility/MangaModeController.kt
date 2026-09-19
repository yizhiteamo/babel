package com.babel.platform.accessibility

import com.babel.core.common.BabelLogger
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ScreenCaptureController
import com.babel.platform.screen.ScreenFrameSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds whether manga mode is on.
 *
 * It lives beside the screenshot source because that is what decides whether
 * the mode can run at all: no accessibility service, no pictures. The mode is
 * now a plain toggle — the consent dialog it used to require went away with
 * MediaProjection (ADR 009).
 */
@Singleton
class MangaModeController @Inject constructor(
    private val frames: ScreenFrameSource,
    private val logger: BabelLogger,
) : ScreenCaptureController {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    override fun start() {
        // Which of the two, because the interface says different things about
        // them: one is the end of the road on this device, the other is a
        // switch the user has not turned on yet.
        if (!frames.isSupported) {
            logger.info(TAG, "manga mode needs a newer Android than this device has")
            _state.value = CaptureState.UNSUPPORTED
            return
        }
        if (!frames.isAvailable) {
            logger.info(TAG, "manga mode needs the accessibility service to be running")
            _state.value = CaptureState.UNAVAILABLE
            return
        }
        _state.value = CaptureState.ACTIVE
        logger.info(TAG, "manga mode on")
    }

    override fun stop() {
        if (_state.value == CaptureState.IDLE) return
        _state.value = CaptureState.IDLE
        logger.info(TAG, "manga mode off")
    }

    /**
     * Called as the service connects and disconnects. Turning the service off
     * while the mode is on has to take the mode with it, or the UI would offer
     * to stop something that is no longer running.
     */
    internal fun onServiceAvailabilityChanged() {
        // UNSUPPORTED is deliberately not recovered from: no service connecting
        // makes an old device new, and a state that flickered back to idle
        // would offer a button that cannot work.
        when {
            frames.isAvailable && _state.value == CaptureState.UNAVAILABLE ->
                _state.value = CaptureState.IDLE

            !frames.isAvailable && _state.value == CaptureState.ACTIVE ->
                _state.value = CaptureState.UNAVAILABLE
        }
    }

    private companion object {
        const val TAG = "MangaMode"
    }
}
