package com.babel.platform.screen

import android.content.Context

/**
 * A context that can add **trusted** overlay windows, when one is available.
 *
 * Windows an accessibility service adds (`TYPE_ACCESSIBILITY_OVERLAY`) are
 * trusted by the input system: one that lets touches through is still drawn
 * fully opaque. An ordinary app overlay that lets touches through is clamped to
 * 80% opacity, which is the 20% of the original that showed through every text
 * translation. Measured on a phone (Android 15): an accessibility overlay with
 * `FLAG_NOT_TOUCHABLE` reported `alpha=1`, hid the text beneath it entirely, and
 * a swipe starting on it scrolled the app below.
 *
 * Here because the accessibility module owns the service and the overlay module
 * draws, and neither may see the other (`CLAUDE.md`).
 */
interface TrustedWindows {

    /** The service's own context while it is connected, or null. */
    val context: Context?
}

/**
 * Hands a tap on to whatever is under Babel's windows.
 *
 * A game's translation sits on the dialogue box, and tapping that box is how a
 * player advances the story; a translation that kept the tap would stop the
 * game. Implemented by the accessibility service (`dispatchGesture`). Measured
 * on a phone: the game accepts the injected tap, ~20-30 ms after release
 * (`docs/milestones/v3.md`, spike 3).
 */
interface TapForwarder {

    /** Whether a tap can be handed on right now (the service is connected). */
    val canForward: Boolean

    /** Taps the screen at ([x], [y]); [done] runs once it was delivered or failed. */
    fun tap(x: Float, y: Float, done: () -> Unit)

    /**
     * Drags along [points] (screen x, y pairs, in order) over [durationMs]:
     * a swipe that began on a translation, handed on when the finger lifts.
     */
    fun drag(points: List<Pair<Float, Float>>, durationMs: Long, done: () -> Unit) = done()
}
