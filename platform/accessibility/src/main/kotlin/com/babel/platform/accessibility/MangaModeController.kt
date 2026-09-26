package com.babel.platform.accessibility

import com.babel.core.common.BabelLogger
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ScreenCaptureController
import com.babel.platform.screen.ScreenFrameSource
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Holds whether manga mode is on.
 *
 * It lives beside the screenshot source because that is what decides whether
 * the mode can run at all: no accessibility service, no pictures. The mode is
 * now a plain toggle — the consent dialog it used to require went away with
 * MediaProjection (ADR 009).
 *
 * ## Why it is written down
 *
 * The state itself is in memory, and the process does not always get to decide
 * when it ends: a phone's power management kills it, the accessibility service
 * goes with it, and the user is back to switching everything on by hand. They
 * reported exactly that.
 *
 * So the user's own choice is persisted and restored when the service comes
 * back. **Only their own choice** — [onServiceAvailabilityChanged] never
 * writes, because a mode taken down by a capability going away is not a
 * decision to turn it off, and recording it as one would mean they had never
 * turned it on. That separation is what [activate] exists for: it moves the
 * state without saying anything about intent.
 */
@Singleton
class MangaModeController(
    private val frames: ScreenFrameSource,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val logger: BabelLogger,
) : ScreenCaptureController {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    override fun start() {
        // Remembered only when it actually came on. Recording the intent behind
        // a failed start would have the mode switch itself on later, on a
        // device where the user was told it could not run.
        if (activate()) remember(on = true)
    }

    override fun stop() {
        if (_state.value == CaptureState.IDLE) return
        _state.value = CaptureState.IDLE
        logger.info(TAG, "manga mode off")
        remember(on = false)
    }

    /**
     * Moves the state, and says nothing about whether the user asked.
     *
     * @return whether the mode is now actually running.
     */
    private fun activate(): Boolean {
        // Which of the two, because the interface says different things about
        // them: one is the end of the road on this device, the other is a
        // switch the user has not turned on yet.
        if (!frames.isSupported) {
            logger.info(TAG, "manga mode needs a newer Android than this device has")
            _state.value = CaptureState.UNSUPPORTED
            return false
        }
        if (!frames.isAvailable) {
            logger.info(TAG, "manga mode needs the accessibility service to be running")
            _state.value = CaptureState.UNAVAILABLE
            return false
        }
        _state.value = CaptureState.ACTIVE
        logger.info(TAG, "manga mode on")
        return true
    }

    private fun remember(on: Boolean) {
        scope.launch { settings.setMangaMode(on) }
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

        // The service is back and the mode is off. If the user had it on, this
        // is the process that was killed under them coming back, not a fresh
        // start — so put it back the way they left it. Through [activate], not
        // [start]: there is nothing new to record, and writing on every connect
        // would be a write per service bind for no reason.
        if (frames.isAvailable && _state.value == CaptureState.IDLE) {
            scope.launch {
                if (settings.settings.first().mangaMode) activate()
            }
        }
    }

    private companion object {
        const val TAG = "MangaMode"
    }
}
