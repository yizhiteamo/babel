package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Recognising a balloon as one already read.
 *
 * The numbers here are not invented. `ScrollMatchMeasurementTest` scrolled a
 * real webtoon five times on the emulator and reported what the detector
 * actually does: the same balloon comes back within a few pixels, and the shift
 * is recoverable from the boxes alone — 696 against an actual 700, every time.
 * The jitter modelled below is that measurement's.
 *
 * Two things are being protected, and they pull against each other. Missing a
 * match costs one recognition, which is what happened before any of this.
 * Making a wrong one puts somebody else's words in a balloon, which is worse
 * than being slow — so every case that could produce one is here.
 */
class ScrolledBalloonsTest {

    private fun box(left: Int, top: Int, width: Int, height: Int) =
        TextBounds(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            space = CoordinateSpace.SCREEN,
        )

    /** The same balloons, moved up by [by], each landing a few pixels off. */
    private fun scrolled(boxes: List<TextBounds>, by: Int, jitter: List<Int> = emptyList()) =
        boxes.mapIndexed { index, it ->
            val off = jitter.getOrElse(index) { 0 }
            box(it.left + off, it.top - by + off, it.width, it.height)
        }

    private val page = listOf(
        box(120, 240, 300, 180),
        box(520, 700, 260, 140),
        box(160, 1180, 340, 220),
        box(600, 1520, 280, 160),
    )

    @Test
    fun `a scroll is recognised as a scroll, and by how far`() {
        val after = scrolled(page, by = 700)
        assertEquals(700, ScrolledBalloons.shiftBetween(page, after))
    }

    @Test
    fun `the few pixels a detector moves a box by do not break the match`() {
        // What the measurement showed: the detector runs on a 640x640 resize of
        // the whole screen, so a scrolled screen is different input and the box
        // lands slightly differently. Four pixels is the size of it.
        val after = scrolled(page, by = 700, jitter = listOf(0, 3, -2, 4))

        val shift = ScrolledBalloons.shiftBetween(page, after)
        assertTrue(shift != null && shift in 692..700, "recovered $shift, expected about 700")

        val matched = after.count { now -> page.any { ScrolledBalloons.isSame(it, now, shift!!) } }
        assertEquals(4, matched, "all four should be recognised despite the jitter")
    }

    @Test
    fun `a page that changed rather than moved matches nothing`() {
        // A chapter ending, or the user leaving for another app. The honest
        // answer is that nothing is known, and the caller then reads it all —
        // which is exactly what it did before this existed.
        val other = listOf(
            box(80, 90, 190, 410),
            box(430, 615, 520, 95),
            box(210, 1333, 145, 275),
        )
        assertNull(ScrolledBalloons.shiftBetween(page, other))
    }

    @Test
    fun `one agreeing pair is a coincidence, not a scroll`() {
        // Two balloons of the same size are common; two *pairs* agreeing on the
        // same shift is not. Requiring two costs one re-read on a screen with a
        // single balloon and rules out the cheapest way to show wrong words.
        val before = listOf(box(120, 240, 300, 180))
        val after = listOf(box(120, 40, 300, 180), box(600, 900, 155, 480))
        assertNull(ScrolledBalloons.shiftBetween(before, after))
    }

    @Test
    fun `an empty side is not a scroll`() {
        assertNull(ScrolledBalloons.shiftBetween(emptyList(), page))
        assertNull(ScrolledBalloons.shiftBetween(page, emptyList()))
    }

    @Test
    fun `a balloon that stays put is matched with no shift at all`() {
        // The frame after Babel takes its own overlays down: the page has
        // changed by the signature's reckoning, and has not moved at all.
        // Reusing every reading is the right answer here, not a degenerate one.
        assertEquals(0, ScrolledBalloons.shiftBetween(page, page))
        assertTrue(page.all { ScrolledBalloons.isSame(it, it, 0) })
    }

    @Test
    fun `a different balloon at the right height is not the same balloon`() {
        val then = box(120, 240, 300, 180)
        // Same left, same top once shifted, and half the height. Matching on
        // position alone would take it, and a reader would get the wrong
        // sentence in a balloon that never said it.
        assertFalse(ScrolledBalloons.isSame(then, box(120, -460, 300, 90), shift = 700))
        // Same again for width, which a two-column layout supplies readily.
        assertFalse(ScrolledBalloons.isSame(then, box(120, -460, 140, 180), shift = 700))
    }

    @Test
    fun `a balloon that moved sideways is not tracked`() {
        // Horizontal movement is a different situation from a webtoon scroll —
        // a zoom, a pan, a relayout — and the reading cannot be trusted to
        // belong to the same balloon. Deliberately out of scope.
        val then = box(120, 240, 300, 180)
        assertFalse(ScrolledBalloons.isSame(then, box(400, -460, 300, 180), shift = 700))
    }

    @Test
    fun `the shift is the one most pairs agree on, not the first that fits`() {
        // One stray box that happens to fit some other offset must not outvote
        // the three that moved together.
        val after = scrolled(page.take(3), by = 700) + box(120, 240, 300, 180)
        assertEquals(700, ScrolledBalloons.shiftBetween(page, after))
    }

    @Test
    fun `scrolling back up is a scroll too`() {
        val after = scrolled(page, by = -700)
        assertEquals(-700, ScrolledBalloons.shiftBetween(page, after))
        assertTrue(after.all { now -> page.any { ScrolledBalloons.isSame(it, now, -700) } })
    }
}
