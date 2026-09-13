package com.babel.domain.vision

import com.babel.domain.acquisition.TextSource
import kotlinx.coroutines.flow.StateFlow

/**
 * What manga mode is doing.
 *
 * Deliberately not folded into [com.babel.core.model.CapabilityState]: the other
 * capabilities are permissions the user grants in system settings, whereas this
 * is a mode the user turns on and off inside the app.
 */
enum class CaptureState {
    /** Off. Nothing is being read from the screen as an image. */
    IDLE,

    /** On. Frames are being read and recognised. */
    ACTIVE,

    /**
     * Cannot be turned on here: the device is below Android 11, or the
     * accessibility service that takes the pictures is not running.
     */
    UNAVAILABLE,

    /** The last attempt failed; see logs. */
    FAILED,
}

/**
 * Turns manga mode on and off.
 *
 * There used to be no `start` here, because MediaProjection consent had to be
 * collected by an Activity and so a session could only begin from the UI layer.
 * Frames now come from the accessibility service the user has already
 * authorised, so there is no dialog and no reason to withhold `start`
 * (ADR 009).
 */
interface ScreenCaptureController {

    val state: StateFlow<CaptureState>

    /** Turns the mode on. No-op when the state is [CaptureState.UNAVAILABLE]. */
    fun start()

    /** Turns the mode off and drops anything it was showing. */
    fun stop()
}

/**
 * Reads the screen once and publishes whatever text it finds.
 *
 * Free of Android types on purpose, so the accessibility service can drive the
 * scan loop without being able to see the OCR module — which would drag ML Kit
 * into the V1 path. The pixels themselves travel by a separate route that never
 * passes through the domain.
 */
interface ImageTextScanner : TextSource {

    /**
     * @param packageName what is in front, stamped onto every element produced.
     *   Without it the privacy policy's per-app exclusions cannot match, since
     *   they key on the package — a capture has no window to learn it from, so
     *   whoever drives the scan has to say.
     */
    suspend fun scanOnce(packageName: String?)

    /** Drops everything currently tracked, e.g. when the mode is turned off. */
    suspend fun clear()
}
