package com.babel.domain.vision

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The picture settling what the balloons cannot: a page whose balloons repeat
 * at a pitch, over artwork that does not.
 */
class PageMotionTest {

    private val rows = 200
    private val bands = 8
    private val height = 3200
    private val rowPx = height / rows

    /** A tall page of artwork, one value per 16px row and band. */
    private val artwork: List<ByteArray> = Random(7).let { random ->
        List(2000) { ByteArray(bands) { random.nextInt(256).toByte() } }
    }

    /** The screen showing the page from [offset] px down. */
    private fun screen(offset: Int, page: List<ByteArray> = artwork): PageMotion.Profile {
        val cells = ByteArray(rows * bands)
        for (r in 0 until rows) page[offset / rowPx + r].copyInto(cells, r * bands)
        return PageMotion.Profile(cells, rows, bands, height)
    }

    private fun flat(): PageMotion.Profile = PageMotion.Profile(ByteArray(rows * bands) { 90 }, rows, bands, height)

    /** The measured failure: the balloons vote one period off, the picture says where the page went. */
    @Test
    fun `a shift one period off is replaced by the one the picture agrees with`() {
        val before = screen(1600)
        val after = screen(1600 + 720)

        val chosen = PageMotion.choose(before, after, previousOffset = 1600, geometric = 1600 + 20, candidates = listOf(1600 + 20, 1600 + 720))

        assertEquals(1600 + 720, chosen)
    }

    @Test
    fun `a shift the picture agrees with stands`() {
        val chosen = PageMotion.choose(screen(1600), screen(2320), 1600, geometric = 2320, candidates = listOf(2320, 3020))

        assertEquals(2320, chosen)
    }

    /** No candidate fits the picture: read the screen again rather than guess. */
    @Test
    fun `nothing the picture agrees with means a fresh read`() {
        val chosen = PageMotion.choose(screen(1600), screen(2320), 1600, geometric = 1620, candidates = listOf(1620, 2900))

        assertNull(chosen)
    }

    /** A tie the picture can break, it breaks. */
    @Test
    fun `a tie is settled when exactly one candidate fits`() {
        val chosen = PageMotion.choose(screen(1600), screen(2320), 1600, geometric = null, candidates = listOf(1620, 2320))

        assertEquals(2320, chosen)
    }

    /** Scrolled back further than a screen: the frames share nothing, so geometry decides. */
    @Test
    fun `a shift too far to compare is left to geometry`() {
        assertEquals(0, PageMotion.choose(screen(4000), screen(0), 4000, geometric = 0, candidates = listOf(0)))
    }

    /**
     * Measured: the balloons once voted for a shift 4875px away, too far for
     * the frames to overlap, while the true 720 fitted the picture.
     */
    @Test
    fun `a shift too far to see loses to one the picture confirms`() {
        val chosen = PageMotion.choose(screen(6000), screen(6720), 6000, geometric = 6000 - 4875, candidates = listOf(6000 - 4875, 6720))

        assertEquals(6720, chosen)
    }

    /** Measured at the end of a page, where Edge's bar comes back and the frame shrinks. */
    @Test
    fun `frames of different heights are not compared`() {
        val shorter = PageMotion.Profile(screen(2320).cells, rows, bands, 2677)

        assertEquals(1620, PageMotion.choose(screen(1600), shorter, 1600, geometric = 1620, candidates = listOf(1620)))
    }

    /**
     * A picture that repeats as exactly as the balloons agrees with every
     * candidate and so says nothing: geometry's own answer stands, and a tie
     * stays a tie.
     */
    @Test
    fun `a flat picture neither overrules geometry nor breaks a tie`() {
        assertEquals(2320, PageMotion.choose(flat(), flat(), 1600, geometric = 2320, candidates = listOf(2320, 3020)))
        assertNull(PageMotion.choose(flat(), flat(), 1600, geometric = null, candidates = listOf(2320, 3020)))
    }
}
