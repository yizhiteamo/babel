package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangedBandTest {

    private val columns = 4

    @Test
    fun `an unchanged frame has no band`() {
        val grid = IntArray(12) { 100 }
        assertNull(ChangedBand.of(grid, grid.copyOf(), columns))
    }

    @Test
    fun `the band spans the changed rows`() {
        val before = IntArray(12) { 100 }
        val after = before.copyOf().also { it[5] = 200; it[9] = 30 }

        assertEquals(ChangedBand.Rows(1, 2), ChangedBand.of(before, after, columns))
    }

    /** Video compression noise and a gradient's dithering move a cell by a few levels. */
    @Test
    fun `a small flicker is not a change`() {
        val before = IntArray(12) { 100 }
        val after = before.copyOf().also { it[5] = 104 }

        assertNull(ChangedBand.of(before, after, columns))
    }

    @Test
    fun `nothing to compare with is the whole frame`() {
        assertEquals(ChangedBand.Rows(0, 2), ChangedBand.of(null, IntArray(12), columns))
    }

    private fun line(text: String, top: Int) =
        GameLine(text, TextBounds(0, top, 500, top + 50, CoordinateSpace.SCREEN), 1.0)

    @Test
    fun `lines outside the band are kept and the band's are replaced`() {
        val menu = line("MENU", 40)
        val oldDialogue = line("旧い台詞", 1100)
        val newDialogue = line("新しい台詞", 1100)

        val merged = ChangedBand.merge(listOf(menu, oldDialogue), listOf(newDialogue), top = 1000, bottom = 1300)

        assertEquals(listOf(menu, newDialogue), merged)
    }

    @Test
    fun `a line reaching into the band is replaced too`() {
        val straddling = line("跨る", 980)

        assertEquals(emptyList(), ChangedBand.merge(listOf(straddling), emptyList(), top = 1000, bottom = 1300))
    }

    @Test
    fun `a band through a line grows to take the whole line`() {
        val first = line("私はレフ・ライノール。", 1159)
        val second = line("ここで働く技師の一人だ。", 1284)

        val (top, bottom) = ChangedBand.widened(listOf(first, second), top = 1200, bottom = 1300, margin = 20, frameHeight = 1440)

        assertEquals(1139, top)
        assertEquals(true, bottom >= second.bounds.bottom)
    }

    @Test
    fun `a band clear of every line stays as it is`() {
        val first = line("私はレフ・ライノール。", 1159)

        assertEquals(400 to 500, ChangedBand.widened(listOf(first), top = 400, bottom = 500, margin = 20, frameHeight = 1440))
    }
}
