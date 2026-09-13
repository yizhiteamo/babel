package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenes are drawn rather than described, so the expectations are about a shape
 * a reader can picture.
 */
class BubbleBoundsTest {

    private val frame = bounds(0, 0, 1000, 1000)

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        TextBounds(left, top, right, bottom, CoordinateSpace.SCREEN)

    /** A rectangular bubble interior; everything outside it is ink or panel. */
    private fun rectangularBubble(area: TextBounds): (Int, Int) -> Boolean = { x, y ->
        x >= area.left && x < area.right && y >= area.top && y < area.bottom
    }

    /** An elliptical interior, as an actual speech balloon is. */
    private fun ellipticalBubble(area: TextBounds): (Int, Int) -> Boolean = { x, y ->
        val cx = (area.left + area.right) / 2.0
        val cy = (area.top + area.bottom) / 2.0
        val rx = area.width / 2.0
        val ry = area.height / 2.0
        val dx = (x - cx) / rx
        val dy = (y - cy) / ry
        dx * dx + dy * dy <= 1.0
    }

    /**
     * The point of the whole exercise: a narrow vertical column of Japanese
     * must come back wide enough to hold a horizontal line of Chinese.
     */
    @Test
    fun `a narrow column grows to the bubble that holds it`() {
        val bubble = bounds(100, 100, 400, 300)
        val column = bounds(230, 150, 270, 250)

        val grown = BubbleBounds.expand(column, frame, rectangularBubble(bubble))

        assertTrue(grown.width > column.width * 3, "expected a much wider box, got ${grown.width}")
        assertTrue(grown.left >= bubble.left && grown.right <= bubble.right)
        assertTrue(grown.top >= bubble.top && grown.bottom <= bubble.bottom)
    }

    /** A real balloon is round, so the result is a rectangle inscribed in it. */
    @Test
    fun `an elliptical bubble yields a box inside it`() {
        val bubble = bounds(100, 100, 500, 400)
        val column = bounds(290, 220, 320, 280)

        val grown = BubbleBounds.expand(column, frame, ellipticalBubble(bubble))

        // Width is what a horizontal translation needs, and a balloon has
        // plenty of it. Settling for a tall sliver is the bug this guards.
        assertTrue(grown.width > column.width * 5, "expected a wide box, got ${grown.width}")
        assertTrue(grown.width > grown.height, "expected wider than tall, got $grown")
        // Every corner has to be inside the ellipse, or the box overlaps the outline.
        val inside = ellipticalBubble(bubble)
        assertTrue(inside(grown.left, grown.top) && inside(grown.right - 1, grown.top))
        assertTrue(inside(grown.left, grown.bottom - 1) && inside(grown.right - 1, grown.bottom - 1))
    }

    /** Text tight against its bubble has nowhere to go, and that is fine. */
    @Test
    fun `a box already filling its bubble barely moves`() {
        val bubble = bounds(100, 100, 200, 200)
        val text = bounds(105, 105, 195, 195)

        val grown = BubbleBounds.expand(text, frame, rectangularBubble(bubble))

        assertTrue(grown.width <= bubble.width && grown.height <= bubble.height)
    }

    /**
     * Text on open background is not in a bubble, and must be left where it is.
     */
    @Test
    fun `text with nothing around it is left alone`() {
        val text = bounds(400, 400, 460, 500)

        assertEquals(text, BubbleBounds.expand(text, frame) { _, _ -> true })
    }

    /**
     * A browser's tab title sits on a toolbar: enclosed above and below, open
     * to both sides. Growing it produced a large overlay over browser chrome on
     * device — far more intrusive than the small one it replaced.
     */
    @Test
    fun `text on a strip open at both ends is left alone`() {
        val strip = bounds(0, 60, 1000, 140)
        val label = bounds(300, 80, 420, 120)

        assertEquals(label, BubbleBounds.expand(label, frame, rectangularBubble(strip)))
    }

    /** A strip that does end, like a button, is an enclosure like any other. */
    @Test
    fun `a bounded strip still counts as an enclosure`() {
        val chip = bounds(200, 60, 500, 140)
        val label = bounds(300, 80, 420, 120)

        val grown = BubbleBounds.expand(label, frame, rectangularBubble(chip))

        assertTrue(grown.width > label.width, "expected growth inside the chip")
        assertTrue(grown.left >= chip.left && grown.right <= chip.right)
    }

    @Test
    fun `expansion never leaves the frame`() {
        val bubble = bounds(0, 0, 300, 300)
        val text = bounds(120, 120, 180, 180)

        val grown = BubbleBounds.expand(text, frame, rectangularBubble(bubble))

        assertTrue(grown.left >= frame.left && grown.top >= frame.top, "grown=$grown")
        assertTrue(grown.right <= frame.right && grown.bottom <= frame.bottom, "grown=$grown")
    }

    @Test
    fun `an empty box is returned untouched`() {
        val empty = bounds(10, 10, 10, 10)

        assertEquals(empty, BubbleBounds.expand(empty, frame) { _, _ -> true })
    }
}
