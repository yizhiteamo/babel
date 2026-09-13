package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.domain.vision.ColorAnalysis

/**
 * Reduces a frame to a coarse luminance grid, cheap enough to compute on every
 * frame and stable enough that an unchanged page produces an identical result.
 *
 * [FrameChangeDetector][com.babel.domain.vision.FrameChangeDetector] does the
 * comparing; this only knows how to shrink a `Bitmap`.
 */
internal object FrameSignature {

    /**
     * Coarse on purpose. The question is "is this a different page", not "did a
     * pixel move", and a fine grid would answer the second — making a blinking
     * cursor look like a page turn.
     */
    private const val COLUMNS = 32
    private const val ROWS = 18

    fun of(frame: Bitmap): IntArray = runCatching {
        val scaled = Bitmap.createScaledBitmap(frame, COLUMNS, ROWS, true)
        val pixels = IntArray(COLUMNS * ROWS)
        scaled.getPixels(pixels, 0, COLUMNS, 0, 0, COLUMNS, ROWS)
        if (scaled !== frame) scaled.recycle()

        IntArray(pixels.size) { ColorAnalysis.luminance(pixels[it]).toInt() }
    }.getOrDefault(IntArray(0))
}
