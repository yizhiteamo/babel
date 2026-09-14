package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameChangeDetectorTest {

    private val cells = 1000

    private fun page(value: Int = 240) = IntArray(cells) { value }

    /** Change some share of the cells to a clearly different luminance. */
    private fun IntArray.altering(fraction: Double): IntArray =
        copyOf().also { for (i in 0 until (cells * fraction).toInt()) it[i] = 10 }

    @Test
    fun `the first frame of a session is always recognised`() {
        assertTrue(FrameChangeDetector.shouldRecognize(null, page()))
    }

    @Test
    fun `the same page again is not recognised twice`() {
        assertFalse(FrameChangeDetector.shouldRecognize(page(), page()))
    }

    /**
     * The captured screen includes our own overlays, so a translation appearing
     * is a real difference in the frame. It must not be mistaken for a page
     * turn, or the pipeline scans its own output forever.
     */
    @Test
    fun `our own translations appearing do not count as a new page`() {
        val withOverlays = page().altering(fraction = 0.10)

        assertFalse(FrameChangeDetector.shouldRecognize(page(), withOverlays))
    }

    @Test
    fun `turning the page is recognised`() {
        val nextPage = page().altering(fraction = 0.85)

        assertTrue(FrameChangeDetector.shouldRecognize(page(), nextPage))
    }

    /** Capture noise is not content. */
    @Test
    fun `small per-cell wobble is ignored`() {
        val noisy = IntArray(cells) { 240 + (it % 5) - 2 }

        assertFalse(FrameChangeDetector.shouldRecognize(page(), noisy))
    }

    /** A rotation changes the grid; there is nothing to compare against. */
    @Test
    fun `a differently shaped signature forces recognition`() {
        assertTrue(FrameChangeDetector.shouldRecognize(IntArray(10), IntArray(20)))
    }

    /**
     * Nothing has settled before there is a frame to compare against, so the
     * first tick of a session buys one interval of patience rather than reading
     * whatever happens to be on screen.
     */
    @Test
    fun `nothing has settled before the first frame`() {
        assertFalse(FrameChangeDetector.hasSettled(null, page()))
    }

    @Test
    fun `a page that has not moved has settled`() {
        assertTrue(FrameChangeDetector.hasSettled(page(), page()))
    }

    /**
     * The case this exists for: the accessibility tree described the comic
     * while the browser was still painting the article it was leaving, and the
     * capture read the outgoing page onto the incoming one.
     */
    @Test
    fun `a page in transition has not settled`() {
        val midTransition = page().altering(fraction = 0.40)

        assertFalse(FrameChangeDetector.hasSettled(page(), midTransition))
    }

    /**
     * Settling and changing are asked of different pairs, so a real page turn
     * that has finished drawing answers yes to both — otherwise a new page
     * would never be read at all.
     */
    @Test
    fun `a finished page turn has both settled and changed`() {
        val turned = page().altering(fraction = 0.80)

        assertTrue(FrameChangeDetector.hasSettled(turned, turned))
        assertTrue(FrameChangeDetector.shouldRecognize(page(), turned))
    }
}
