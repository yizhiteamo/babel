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
