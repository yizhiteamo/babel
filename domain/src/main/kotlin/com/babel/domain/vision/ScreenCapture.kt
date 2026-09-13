package com.babel.domain.vision

import kotlinx.coroutines.flow.StateFlow

/**
 * What screen capture is doing.
 *
 * Deliberately not folded into [com.babel.core.model.CapabilityState]: the other
 * capabilities are permissions granted once and then simply present, whereas
 * capture is a **session**. From Android 15 the user must re-authorise every
 * session and a token cannot be reused, so "granted" is not a state this can
 * ever be in.
 */
enum class CaptureState {
    /** No session. Nothing is being read from the screen. */
    IDLE,

    /** Waiting for the user to allow capture. */
    REQUESTING,

    /** A session is live and frames can be taken. */
    ACTIVE,

    /** The last session ended unexpectedly; see logs. */
    FAILED,
}

/**
 * Observes and ends a capture session.
 *
 * There is no `start` here on purpose: beginning a session requires an Activity
 * to receive the system's consent dialog, so it is initiated from the UI layer
 * and only its lifetime is managed through this contract.
 */
interface ScreenCaptureController {

    val state: StateFlow<CaptureState>

    /** Ends the session and releases the virtual display. */
    fun stop()
}
