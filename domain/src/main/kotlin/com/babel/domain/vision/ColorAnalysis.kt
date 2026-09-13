package com.babel.domain.vision

import com.babel.core.model.SourceStyle

/**
 * Works out what colour a text region sits on, from the pixels inside it.
 *
 * Pure arithmetic on packed ARGB ints, deliberately: this is the part worth
 * testing, and it stays testable only while it knows nothing about `Bitmap`.
 * Reading pixels out of a frame is the platform's job.
 *
 * The method is a histogram. Inside a speech bubble the background is the
 * majority of pixels and the lettering the minority, so the most populous
 * colour is the background — this holds for black-on-white and for inverted
 * panels alike, which is why nothing here assumes comics are white.
 */
object ColorAnalysis {

    /**
     * Colours are bucketed before counting. Anti-aliasing means a white bubble
     * contains hundreds of near-whites and no single exact value would win a
     * count; bucketing collapses them into one peak.
     */
    private const val BUCKET_BITS = 4
    private const val BUCKET_SHIFT = 8 - BUCKET_BITS

    /** Below this the sample is too small for a majority to mean anything. */
    private const val MIN_SAMPLES = 16

    /**
     * A foreground guess is only reported when it is this far from the
     * background, in 0..255 per-channel distance. Text drawn in a colour close
     * to its background would be a bad guess to act on, and the renderer's
     * luminance fallback is the safer answer there.
     */
    private const val MIN_FOREGROUND_DISTANCE = 64

    /**
     * @param pixels packed ARGB, in any order — only the histogram matters.
     */
    fun analyse(pixels: IntArray): SourceStyle {
        if (pixels.size < MIN_SAMPLES) return SourceStyle.UNKNOWN

        val sums = HashMap<Int, LongArray>()
        for (pixel in pixels) {
            val r = (pixel ushr 16) and 0xFF
            val g = (pixel ushr 8) and 0xFF
            val b = pixel and 0xFF
            val bucket = (r shr BUCKET_SHIFT shl (BUCKET_BITS * 2)) or
                (g shr BUCKET_SHIFT shl BUCKET_BITS) or
                (b shr BUCKET_SHIFT)

            // Averaged within the bucket rather than using the bucket's centre:
            // the peak of a white bubble should come back as its actual white,
            // not as the quantised approximation of it.
            val acc = sums.getOrPut(bucket) { LongArray(4) }
            acc[0] += r.toLong()
            acc[1] += g.toLong()
            acc[2] += b.toLong()
            acc[3] += 1L
        }

        val background = sums.maxByOrNull { it.value[3] } ?: return SourceStyle.UNKNOWN
        val backgroundColor = background.value.toColor()

        // The furthest bucket from the background is the lettering, provided
        // enough pixels share it — a handful of outliers is JPEG noise, not text.
        val foreground = sums.asSequence()
            .filter { it.value[3] * MINORITY_DIVISOR >= pixels.size }
            .maxByOrNull { distance(it.value.toColor(), backgroundColor) }
            ?.value
            ?.toColor()

        return SourceStyle(
            backgroundColor = backgroundColor,
            foregroundColor = foreground?.takeIf {
                distance(it, backgroundColor) >= MIN_FOREGROUND_DISTANCE
            },
        )
    }

    /** Perceived brightness, for choosing readable text over a known background. */
    fun luminance(color: Int): Double {
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun LongArray.toColor(): Int {
        val count = this[3].coerceAtLeast(1L)
        val r = (this[0] / count).toInt()
        val g = (this[1] / count).toInt()
        val b = (this[2] / count).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun distance(a: Int, b: Int): Int {
        var total = 0
        for (shift in intArrayOf(16, 8, 0)) {
            total += kotlin.math.abs(((a ushr shift) and 0xFF) - ((b ushr shift) and 0xFF))
        }
        return total / 3
    }

    /** A bucket must hold at least 1/20th of the sample to count as text. */
    private const val MINORITY_DIVISOR = 20
}
