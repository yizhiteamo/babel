package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Colours are written as a page would actually contain them: a majority of
 * background with lettering over it.
 */
class ColorAnalysisTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    private fun page(background: Int, foreground: Int, inkShare: Int): IntArray =
        IntArray(1000) { if (it % 100 < inkShare) foreground else background }

    @Test
    fun `black lettering on a white bubble reads as white`() {
        val style = ColorAnalysis.analyse(page(white, black, inkShare = 20))

        assertEquals(white, style.backgroundColor)
        assertEquals(black, style.foregroundColor)
    }

    /**
     * Nothing may assume comics are white. Inverted panels are common and a
     * hardcoded assumption would paint a white block on a black page.
     */
    @Test
    fun `white lettering on a black panel reads as black`() {
        val style = ColorAnalysis.analyse(page(black, white, inkShare = 20))

        assertEquals(black, style.backgroundColor)
        assertEquals(white, style.foregroundColor)
    }

    /**
     * Bounds hug the text, so ink can be a large share of the sample. The
     * background must still win while it is the majority.
     */
    @Test
    fun `background wins while it is the majority`() {
        val style = ColorAnalysis.analyse(page(white, black, inkShare = 45))

        assertEquals(white, style.backgroundColor)
    }

    /**
     * Anti-aliasing means a real bubble holds hundreds of near-whites and no
     * single exact value is a majority. Bucketing is what makes the peak
     * survive that, and the reported colour has to be an actual white rather
     * than the bucket's quantised centre.
     */
    @Test
    fun `anti-aliased edges do not dissolve the peak`() {
        val pixels = IntArray(1000) { index ->
            when {
                index % 100 < 20 -> black
                // Every remaining pixel a slightly different near-white.
                else -> {
                    val v = 250 + (index % 6)
                    (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                }
            }
        }

        val background = ColorAnalysis.analyse(pixels).backgroundColor!!
        val channel = background and 0xFF

        assertTrue(channel in 250..255, "expected a near-white, got $channel")
    }

    /**
     * Low-contrast lettering is a bad guess to act on; the renderer's luminance
     * fallback is safer than a colour that might be background noise.
     */
    @Test
    fun `text too close to its background is not reported`() {
        val nearWhite = 0xFFEEEEEE.toInt()

        val style = ColorAnalysis.analyse(page(white, nearWhite, inkShare = 20))

        assertEquals(white, style.backgroundColor)
        assertNull(style.foregroundColor)
    }

    /** A few stray pixels are compression noise, not lettering. */
    @Test
    fun `a handful of outliers is not mistaken for text`() {
        val pixels = IntArray(1000) { if (it < 3) black else white }

        assertNull(ColorAnalysis.analyse(pixels).foregroundColor)
    }

    @Test
    fun `too few pixels yields nothing rather than a guess`() {
        assertEquals(0, ColorAnalysis.analyse(IntArray(4) { white }).backgroundColor ?: 0)
    }

    @Test
    fun `luminance orders dark below light`() {
        assertTrue(ColorAnalysis.luminance(black) < ColorAnalysis.luminance(white))
    }
}
