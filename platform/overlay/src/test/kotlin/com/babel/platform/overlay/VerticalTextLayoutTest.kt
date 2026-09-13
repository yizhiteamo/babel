package com.babel.platform.overlay

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VerticalTextLayoutTest {

    private fun layout(
        text: String,
        width: Int,
        height: Int,
        max: Int = 48,
        min: Int = 12,
    ) = VerticalTextLayout.layout(text, width, height, max, min)

    @Test
    fun `a short line fits one column`() {
        val result = assertNotNull(layout("你好世界", width = 200, height = 400))

        assertEquals(1, result.columns.size)
        assertEquals("你好世界", result.columns.single())
    }

    /**
     * A balloon is taller than one column of a long sentence, so the text wraps
     * into further columns — which is exactly what covers the several columns
     * the original occupied.
     */
    @Test
    fun `a long sentence wraps into columns`() {
        val text = "明天的会议是几点开始的呢我记不清了"

        val result = assertNotNull(layout(text, width = 300, height = 200))

        assertTrue(result.columns.size > 1, "expected several columns, got ${result.columns}")
        assertEquals(text, result.columns.joinToString(""))
    }

    /**
     * Columns are returned in writing order and drawn right to left, so the
     * first column holds the start of the sentence.
     */
    @Test
    fun `columns come back in writing order`() {
        val result = assertNotNull(layout("一二三四五六", width = 300, height = 100, max = 40, min = 40))

        // 100px tall at 40px per glyph is two characters a column.
        assertEquals(listOf("一二", "三四", "五六"), result.columns)
    }

    /**
     * The largest size that fits is the one that fills the bubble.
     *
     * The text has to be long enough to actually strain the smaller box: four
     * characters fit at maximum size in almost anything, and a first version of
     * this test compared 48 against 48 and proved nothing.
     */
    @Test
    fun `the biggest workable size wins`() {
        val text = "明天的会议是几点开始的"
        val roomy = assertNotNull(layout(text, width = 400, height = 400))
        val cramped = assertNotNull(layout(text, width = 150, height = 120))

        assertTrue(
            roomy.glyphSizePx > cramped.glyphSizePx,
            "a taller box should allow bigger type: ${roomy.glyphSizePx} vs ${cramped.glyphSizePx}",
        )
        assertTrue(roomy.glyphSizePx <= 48, "must respect the ceiling")
    }

    /**
     * Reporting that it does not fit is a real answer: the caller falls back to
     * horizontal rather than drawing something illegible.
     */
    @Test
    fun `a box too small for the smallest type reports failure`() {
        assertNull(layout("这是一段相当长的文字需要很多空间才放得下", width = 20, height = 20))
    }

    @Test
    fun `empty text has no layout`() {
        assertNull(layout("", width = 200, height = 200))
        assertNull(layout("\n", width = 200, height = 200))
    }

    @Test
    fun `a single character is a single column`() {
        val result = assertNotNull(layout("啊", width = 200, height = 200))

        assertEquals(listOf("啊"), result.columns)
    }

    @Test
    fun `columns never overflow the box width`() {
        val result = assertNotNull(layout("一二三四五六七八九十", width = 150, height = 300))

        assertTrue(
            result.columns.size * result.columnPitchPx <= 150,
            "columns ${result.columns.size} x pitch ${result.columnPitchPx} exceeds 150",
        )
    }

    @Test
    fun `a column never overflows the box height`() {
        val result = assertNotNull(layout("一二三四五六七八九十", width = 300, height = 150))

        val tallest = result.columns.maxOf { it.length }
        assertTrue(
            tallest * result.advancePx <= 150,
            "$tallest chars x ${result.advancePx} exceeds 150",
        )
    }

    @Test
    fun `nonsense bounds are refused rather than guessed at`() {
        assertNull(layout("文字", width = 0, height = 200))
        assertNull(layout("文字", width = 200, height = 0))
        assertNull(VerticalTextLayout.layout("文字", 200, 200, maxGlyphPx = 10, minGlyphPx = 20))
    }
}
