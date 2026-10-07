package com.babel.platform.screen

import android.graphics.Bitmap

/**
 * Hands over a picture of the screen.
 *
 * This module exists for one reason: the contract has to mention [Bitmap], and
 * an Android type cannot appear in the pure-Kotlin `:domain`. Putting it here
 * instead of in either platform module keeps the rule that `:platform:*` never
 * depend on each other — whoever takes the picture and whoever reads it both
 * depend on this, not on one another.
 *
 * That matters concretely: the OCR module pulls in ML Kit, and the accessibility
 * module has no business acquiring that dependency just to hand over pixels.
 */
interface ScreenFrameSource {

    /**
     * Whether this **device** could ever produce frames.
     *
     * Separate from [isAvailable] because the two failures are not alike: a
     * device below the required version can never do this, while a service that
     * is not running is one visit to system settings away. Collapsing them left
     * the interface unable to say which had happened, and saying both is worse
     * than useless on a device where the first is impossible.
     */
    val isSupported: Boolean

    /** Whether frames can be taken **right now**: supported, and connected. */
    val isAvailable: Boolean

    /**
     * A picture of the screen right now, or null when one could not be taken.
     *
     * The caller owns the returned bitmap and should recycle it. Implementations
     * must return a software bitmap: a hardware-backed one cannot be read with
     * `getPixels` and is rejected by the recognisers that consume this.
     */
    suspend fun latestFrame(): Bitmap?

    /**
     * A picture of one window alone, or null when that cannot be had.
     *
     * Game mode reads this rather than [latestFrame]: a picture of the game's
     * window does not contain Babel's own translations, so the next line is
     * still visible once the last one is covered (`docs/milestones/v3.md`,
     * spike 1). Android 14 and up; null everywhere else. Same ownership rule
     * as [latestFrame].
     */
    suspend fun windowFrame(windowId: Int): Bitmap? = null

    /** Whether [windowFrame] can work on this device. */
    val supportsWindowFrames: Boolean get() = false
}
