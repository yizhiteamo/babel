package com.babel.platform.overlay

import android.view.WindowManager
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which of the two arrangements takes touches, and why each answer is the one
 * it is. Both have cost a reported defect, in opposite directions.
 *
 * A plain JVM test, no Robolectric: the flags are compile-time constants, so
 * asserting on the integer needs no device.
 */
class OverlayTouchabilityTest {

    private fun passesTouchesThrough(flags: Int) =
        flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0

    /**
     * V1 must never change how a screen behaves. This was broken exactly once,
     * by giving V1 the manga treatment: its overlays are often large — a
     * container node can report the size of a whole page — and they became
     * solid blocks that ate every tap. Reported from a device within a day.
     */
    @Test
    fun `the V1 container lets every touch reach the app`() {
        assertTrue(passesTouchesThrough(CONTAINER_FLAGS))
    }

    /**
     * And a manga translation deliberately does not — which is a product
     * judgement with a measured price on both sides, not an oversight.
     *
     * | | Swallowed swipes | The original underneath |
     * |---|---|---|
     * | Touchable (this) | **10.5%** of thumb-band start points | covered, `alpha=1.0` |
     * | Passthrough | none | **shows through, `alpha=0.7998`** |
     *
     * Android caps the opacity of an untrusted overlay that lets touches past,
     * so the two cannot both be had: `FLAG_NOT_TOUCHABLE` went on, `dumpsys`
     * reported 0.7998 instead of 1.0, and the Japanese was plainly legible
     * under the Chinese. Stacking windows to claw the opacity back reaches 0.96
     * and trips the same rule's other half, which **blocks** touches to the app
     * below without saying so.
     *
     * This test exists so that adding the flag fails here rather than shipping.
     * Anything that adds it has to answer for the ghost first.
     */
    @Test
    fun `a manga translation takes touches, and the ghost is why`() {
        assertFalse(passesTouchesThrough(OWN_WINDOW_FLAGS))
    }

    @Test
    fun `neither takes focus, so the keyboard is never stolen`() {
        for (flags in listOf(CONTAINER_FLAGS, OWN_WINDOW_FLAGS)) {
            assertTrue(flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        }
    }
}
