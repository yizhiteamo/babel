package com.babel.platform.overlay

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Numbers below are the ones actually observed on a 280dpi device against a
 * WebView page: 8sp ≈ 14px, 24sp ≈ 42px.
 */
class TextFittingTest {

    private val minPx = 14      // 8sp
    private val ceilingPx = 42  // 24sp
    private val glyphRatio = 0.7f
    private val lineSpacing = 1.2f

    private fun maxSize(heightPx: Int) = TextFitting.maxTextSizePx(
        boundsHeightPx = heightPx,
        glyphHeightRatio = glyphRatio,
        ceilingPx = ceilingPx,
        floorPx = minPx,
    )

    private fun lines(heightPx: Int) = TextFitting.maxLines(
        boundsHeightPx = heightPx,
        minTextSizePx = minPx,
        lineSpacingRatio = lineSpacing,
    )

    @Test
    fun `single line of text gets a size proportional to its height`() {
        // A 52px-tall line: 52 * 0.7 = 36px, under the ceiling.
        assertEquals(36, maxSize(52))
    }

    /**
     * Regression: a WebView root node reported the height of the entire view
     * (870px). Unclamped, that asked for 609px type — display-sized letters
     * across half the screen.
     */
    @Test
    fun `a container-sized node does not produce display-sized type`() {
        assertEquals(ceilingPx, maxSize(870))
        assertTrue(maxSize(870) <= ceilingPx)
    }

    @Test
    fun `size never drops to or below the autosize floor`() {
        assertTrue(maxSize(0) > minPx)
        assertTrue(maxSize(1) > minPx)
        assertTrue(maxSize(-5) > minPx)
    }

    /**
     * Regression: line count used to be derived from the *maximum* type size,
     * so a four-line paragraph came out as one line and autosizing shrank the
     * whole translation onto a single illegible row.
     */
    @Test
    fun `a multi-line paragraph is allowed multiple lines`() {
        // 229px paragraph, as reported for a four-line block.
        assertTrue(lines(229) >= 4, "expected room for at least 4 lines, got ${lines(229)}")
    }

    @Test
    fun `a single-line box allows one line`() {
        assertEquals(1, lines(16))
    }

    @Test
    fun `degenerate heights still allow one line`() {
        assertEquals(1, lines(0))
        assertEquals(1, lines(-10))
    }

    @Test
    fun `taller boxes never allow fewer lines`() {
        val heights = listOf(20, 60, 120, 229, 400, 870)
        val counts = heights.map(::lines)
        assertEquals(counts.sorted(), counts, "line allowance must grow with height")
    }
}
