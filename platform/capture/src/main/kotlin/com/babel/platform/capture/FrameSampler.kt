package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.domain.vision.ColorAnalysis

/**
 * Pulls the pixels under a text region out of a frame so its colours can be
 * worked out. The judgement itself lives in [ColorAnalysis]; this only knows
 * how to read a `Bitmap`.
 */
internal object FrameSampler {

    /**
     * Region bounds hug the lettering, so a box on its own can be close to half
     * ink. A margin pulls in more of the surrounding bubble and makes the
     * background a clearer majority.
     *
     * It is deliberately small. Widen it and the sample starts crossing the
     * bubble's outline into the panel behind it, which is the one thing that
     * could actually flip the answer — a modest gain in certainty is not worth
     * sampling a colour that is not the bubble's.
     */
    private const val MARGIN_FRACTION = 0.10f

    /** One frame is ~2 megapixels; a region that large is a recognition error. */
    private const val MAX_SAMPLE_PIXELS = 1 shl 18

    fun sample(frame: Bitmap, bounds: TextBounds): SourceStyle {
        val left = (bounds.left - bounds.width * MARGIN_FRACTION).toInt().coerceIn(0, frame.width)
        val top = (bounds.top - bounds.height * MARGIN_FRACTION).toInt().coerceIn(0, frame.height)
        val right = (bounds.right + bounds.width * MARGIN_FRACTION).toInt().coerceIn(0, frame.width)
        val bottom = (bounds.bottom + bounds.height * MARGIN_FRACTION).toInt().coerceIn(0, frame.height)

        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0 || width * height > MAX_SAMPLE_PIXELS) {
            return SourceStyle.UNKNOWN
        }

        return runCatching {
            val pixels = IntArray(width * height)
            frame.getPixels(pixels, 0, width, left, top, width, height)
            ColorAnalysis.analyse(pixels)
        }.getOrDefault(SourceStyle.UNKNOWN)
    }
}
