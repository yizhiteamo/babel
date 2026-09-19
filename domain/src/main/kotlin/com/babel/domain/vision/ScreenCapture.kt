package com.babel.domain.vision

import com.babel.core.model.TextBounds
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
     * Not now: the accessibility service that takes the pictures is not
     * running. Switching it on in system settings is all this needs, which is
     * why it is not the same answer as [UNSUPPORTED].
     */
    UNAVAILABLE,

    /**
     * Not ever, on this device: manga mode needs Android 11 for
     * `AccessibilityService.takeScreenshot` (ADR 009).
     *
     * Told apart from [UNAVAILABLE] because one is permanent and the other is a
     * single tap. One message covering both named a version requirement first
     * on devices that always satisfy it, and left the reader no way to tell
     * what to do.
     */
    UNSUPPORTED,

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
    /**
     * @param exclusions areas of the screen this scan must ignore, in screen
     *   coordinates. Image translation exists to read what the text path
     *   cannot, so anything the text path can already see — the app's own
     *   chrome, the system bars — is not artwork and is not its business.
     */
    /**
     * @param within the app's content area, when it is known. Text outside it
     *   is the app's own chrome and is never artwork. Null means unrestricted.
     */
    suspend fun scanOnce(
        packageName: String?,
        exclusions: List<TextBounds> = emptyList(),
        within: TextBounds? = null,
    )

    /** Drops everything currently tracked, e.g. when the mode is turned off. */
    suspend fun clear()

    /**
     * Lets go of anything expensive being held to be able to read at all.
     *
     * Separate from [clear], and called far less often: clearing happens on
     * every scroll, while this happens when image translation is finished with
     * for now. A scanner backed by models measured at **589MB** with them
     * loaded, and holding that while the mode is off buys nothing
     * (`docs/milestones/v2.md`).
     *
     * Reading again afterwards must still work — this is a cost to pay again,
     * not a shutdown.
     */
    suspend fun release()
}
