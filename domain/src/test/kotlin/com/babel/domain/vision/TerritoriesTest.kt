package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerritoriesTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        TextBounds(left, top, right, bottom, CoordinateSpace.SCREEN)

    private fun TextBounds.contains(inner: TextBounds) =
        left <= inner.left && top <= inner.top && right >= inner.right && bottom >= inner.bottom

    private fun TextBounds.overlaps(o: TextBounds) =
        left < o.right && o.left < right && top < o.bottom && o.top < bottom

    /** Two columns in one connected balloon, the measured case: 真可怜 / 你睡不着呢. */
    @Test
    fun `two columns side by side split at the gap`() {
        val balloon = box(100, 100, 500, 600)
        val right = Territories.Item(box(320, 150, 380, 450), balloon)
        val left = Territories.Item(box(200, 200, 260, 500), box(80, 120, 480, 620))

        val (r, l) = Territories.of(listOf(right, left))

        assertEquals(290, r.left)
        assertEquals(290, l.right)
        assertTrue(!r.overlaps(l))
    }

    @Test
    fun `lines stacked in one balloon split across the gap`() {
        val balloon = box(0, 0, 400, 400)
        val top = Territories.Item(box(50, 50, 350, 120), balloon)
        val bottom = Territories.Item(box(50, 200, 350, 260), balloon)

        val (t, b) = Territories.of(listOf(top, bottom))

        assertEquals(160, t.bottom)
        assertEquals(160, b.top)
    }

    @Test
    fun `balloons that do not meet are left alone`() {
        val a = Territories.Item(box(0, 0, 60, 200), box(0, 0, 200, 300))
        val b = Territories.Item(box(500, 0, 560, 200), box(400, 0, 700, 300))

        assertEquals(listOf(a.reach, b.reach), Territories.of(listOf(a, b)))
    }

    /**
     * Two columns of one balloon set diagonally: their lettering boxes overlap
     * by a thin strip, measured 4 to 17px on six of seven such pairs. Each
     * gives up half the strip, so the translations meet instead of overlapping.
     */
    @Test
    fun `lettering that overlaps by a strip is split down its middle`() {
        val reach = box(0, 0, 600, 600)
        val right = Territories.Item(box(300, 100, 400, 300), reach)
        val left = Territories.Item(box(186, 210, 316, 450), reach)

        val (r, l) = Territories.of(listOf(right, left))

        assertEquals(308, r.left)
        assertEquals(308, l.right)
        assertTrue(!r.overlaps(l))
    }

    /** The same lettering reported twice is not cut through the middle of its words. */
    @Test
    fun `lettering that overlaps by more than half is left alone`() {
        val reach = box(0, 0, 400, 400)
        val a = Territories.Item(box(100, 100, 220, 300), reach)
        val b = Territories.Item(box(140, 150, 260, 350), reach)

        assertEquals(listOf(reach, reach), Territories.of(listOf(a, b)))
    }

    @Test
    fun `every territory still holds its lettering`() {
        val reach = box(0, 0, 900, 900)
        val items = listOf(
            Territories.Item(box(100, 100, 160, 400), reach),
            Territories.Item(box(180, 120, 240, 380), reach),
            Territories.Item(box(100, 420, 400, 480), reach),
        )

        Territories.of(items).zip(items).forEach { (t, item) -> assertTrue(t.contains(item.text)) }
    }

    @Test
    fun `the answer does not depend on reading order`() {
        val reach = box(0, 0, 900, 900)
        val items = listOf(
            Territories.Item(box(100, 100, 160, 400), reach),
            Territories.Item(box(180, 120, 240, 380), reach),
            Territories.Item(box(100, 420, 400, 480), reach),
        )

        val forward = Territories.of(items)
        val backward = Territories.of(items.reversed()).reversed()

        assertEquals(forward, backward)
    }

    @Test
    fun `open lettering reaches its own size again on every side, within the frame`() {
        val frame = box(0, 0, 1000, 1000)

        assertEquals(box(0, 60, 140, 240), Territories.openReach(box(20, 120, 80, 180), frame))
    }
}
