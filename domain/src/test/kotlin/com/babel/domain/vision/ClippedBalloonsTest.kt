package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which balloons are worth reading on the screen that shows them.
 *
 * The sizes are the measured ones. `ClippedBalloonMeasurementTest` found nine
 * edge-touching detections across five screens of a real webtoon, heights 113
 * to 546 in a 1700 viewport, and none of them filled the viewport outright.
 */
class ClippedBalloonsTest {

    private val viewport = 1700

    private fun box(top: Int, height: Int) = TextBounds(
        left = 120,
        top = top,
        right = 420,
        bottom = top + height,
        space = CoordinateSpace.SCREEN,
    )

    @Test
    fun `a balloon in the middle of the screen is whole`() {
        assertFalse(ClippedBalloons.waitsForMore(box(top = 240, height = 180), viewport))
    }

    @Test
    fun `a balloon running off the bottom has more to come`() {
        // 419px at the bottom edge, which came back 667px tall on the next
        // screen. Read here, it would be two thirds of a sentence.
        assertTrue(ClippedBalloons.waitsForMore(box(top = 1281, height = 419), viewport))
    }

    @Test
    fun `a balloon cut off at the top has already been read whole`() {
        // The worst case, and the reason the top edge counts as much as the
        // bottom: this balloon was complete on the previous screen. Read again
        // as a fragment it produces different text, which means a different id,
        // which takes a correct translation off the screen.
        assertTrue(ClippedBalloons.waitsForMore(box(top = 0, height = 347), viewport))
    }

    @Test
    fun `a few pixels of clearance is still whole`() {
        // The boxes jitter by about 4px between detections, so the edge test
        // has to have that much give or the same balloon flips between being
        // read and being skipped as the page settles.
        assertFalse(ClippedBalloons.waitsForMore(box(top = 12, height = 300), viewport))
        assertFalse(ClippedBalloons.waitsForMore(box(top = 1388, height = 300), viewport))
    }

    @Test
    fun `a balloon taller than the viewport is read where it is`() {
        // Touching both edges: scrolling will never make it whole, so waiting
        // means never reading it. Nothing on the sample material does this, and
        // the case is covered because the cost of getting it wrong is a balloon
        // that is silently never translated.
        assertFalse(ClippedBalloons.waitsForMore(box(top = 0, height = viewport), viewport))
        assertFalse(ClippedBalloons.waitsForMore(box(top = -40, height = viewport + 80), viewport))
    }
}
