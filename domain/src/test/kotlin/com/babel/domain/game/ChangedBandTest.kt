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

    @Test
    fun `the changed columns are found`() {
        val before = IntArray(12) { 100 }
        val after = before.copyOf().also { it[5] = 200; it[10] = 30 }

        assertEquals(1..2, ChangedBand.columnsOf(before, after, columns))
    }

    @Test
    fun `a box keeps the lines beside it and takes the new reading inside`() {
        fun line(text: String, l: Int, t: Int) = GameLine(text, TextBounds(l, t, l + 100, t + 40, CoordinateSpace.SCREEN), 0.0)
        val list = line("リオ", 100, 500)
        val bubble = line("old", 1000, 500)
        val moved = line("new", 1000, 600)

        val merged = ChangedBand.mergeBox(listOf(list, bubble), listOf(moved), left = 900, top = 0, right = 1500, bottom = 1440)

        assertEquals(listOf("リオ", "new"), merged.map { it.text })
    }

    @Test
    fun `a box is widened past a line it would cut`() {
        val line = GameLine("long", TextBounds(800, 500, 1200, 540, CoordinateSpace.SCREEN), 0.0)

        assertEquals(790 to 1500, ChangedBand.widenedAcross(listOf(line), 1000, 1500, 0, 1440, margin = 10, frameWidth = 3200))
    }

    @Test
    fun `changes far apart are separate regions`() {
        val cols = 10
        val before = IntArray(30) { 100 }
        // Column 0 on row 0, columns 6–7 on rows 1–2.
        val after = before.copyOf().also { it[0] = 200; it[16] = 200; it[27] = 200 }

        assertEquals(
            listOf(ChangedBand.Region(0..0, 0..0), ChangedBand.Region(6..7, 1..2)),
            ChangedBand.regions(before, after, cols, gap = 3),
        )
    }
}
