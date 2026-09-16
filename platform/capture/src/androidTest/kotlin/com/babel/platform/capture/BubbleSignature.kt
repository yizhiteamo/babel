package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.domain.vision.ColorAnalysis

/**
 * **An idea that was measured and does not work.** It lives in the test source
 * set, with `BubbleSignatureTest`, as the evidence for why — not in the app.
 *
 * Identifies a balloon by what it looks like, so the same one is not read twice.
 *
 * Every scroll clears the image path and the next settled frame is read from
 * scratch — eight balloons, 3.1s, every time (`docs/milestones/v2.md`). Most of
 * those balloons were on screen a moment ago and have not changed; only their
 * position has. So the key is the **picture**, never the position.
 *
 * ## Why the picture has to be trimmed first
 *
 * Measured, and it ruled out the obvious version. Across a 120px scroll, five
 * balloons were detected on both frames and **not one** got an identical box:
 * the detector runs on a 640×640 resize of the whole screen, so a screen that
 * has moved is different input and its box lands a few pixels off. Hashing that
 * crop directly never matched. Comparing with a tolerance is worse still — a
 * balloon shifted by one pixel scores *further* from itself than from a
 * different balloon (24.8 per cell against 11.4), so no threshold exists.
 *
 * What does not move is the lettering. So the crop is trimmed to its ink before
 * anything else, which turns a wobbling box around fixed content into fixed
 * content.
 *
 * ## Not [FrameSignature], despite the resemblance
 *
 * That one answers "is this a different page" and is compared with a tolerance.
 * This is a **key**: two balloons sharing it means one balloon's translation
 * drawn over another, which is worse than any amount of slowness. So it is
 * compared for equality, and `BubbleSignatureTest` is what keeps it honest.
 */
@JvmInline
value class BubbleSignature private constructor(private val cells: Int) {

    companion object {
        /**
         * Working resolution for finding the ink — fine enough to place the
         * lettering's edge within a couple of source pixels, cheap enough to run
         * once per balloon.
         */
        private const val WORKING = 64

        /**
         * Cells across the trimmed lettering. The trimming is what makes this
         * stable; the grid only has to tell two balloons apart.
         */
        private const val GRID = 16

        /** Luminance quantised before hashing, to absorb resampling noise. */
        private const val LEVELS = 8

        /**
         * How far below the crop's brightest area a cell must be to count as
         * ink. Relative rather than absolute, because a balloon may be white on
         * black as readily as black on white.
         */
        private const val INK_FRACTION = 0.7

        /** Below this the crop is flat — no lettering to anchor on. */
        private const val MIN_CONTRAST = 40

        /** Null when the bitmap cannot be read; the caller then does not cache. */
        fun of(crop: Bitmap): BubbleSignature? = of(crop, GRID, LEVELS)

        /**
         * The same thing with the balance made explicit, so a test can sweep it.
         *
         * Not for production use: [GRID] and [LEVELS] were chosen by running
         * this across real pages, and a caller picking its own would be choosing
         * a collision rate nobody measured.
         */
        fun of(crop: Bitmap, grid: Int, levels: Int): BubbleSignature? {
            val cells = gridOf(crop, grid) ?: return null
            var hash = 17
            for (value in cells) hash = hash * 31 + value * levels / 256
            return BubbleSignature(hash)
        }

        /** The trimmed luminance grid, so a test can measure distances too. */
        fun gridOf(crop: Bitmap, grid: Int): IntArray? = runCatching {
            val scaled = Bitmap.createScaledBitmap(crop, WORKING, WORKING, true)
            val pixels = IntArray(WORKING * WORKING)
            scaled.getPixels(pixels, 0, WORKING, 0, 0, WORKING, WORKING)
            if (scaled !== crop) scaled.recycle()

            val luminance = IntArray(pixels.size) { ColorAnalysis.luminance(pixels[it]).toInt() }
            luminance.inkBounds()?.let { luminance.resample(it, grid) }
        }.getOrNull()

        /**
         * The rectangle the lettering occupies, as left/top/right/bottom in
         * working-grid cells, or null when the crop carries no lettering.
         */
        private fun IntArray.inkBounds(): IntArray? {
            val brightest = maxOrNull() ?: return null
            val darkest = minOrNull() ?: return null
            if (brightest - darkest < MIN_CONTRAST) return null
            val threshold = darkest + (brightest - darkest) * INK_FRACTION

            var left = WORKING
            var top = WORKING
            var right = -1
            var bottom = -1
            for (index in indices) {
                if (this[index] >= threshold) continue
                val x = index % WORKING
                val y = index / WORKING
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
            return if (right < left || bottom < top) null else intArrayOf(left, top, right, bottom)
        }

        /** Averages the working grid's ink rectangle down to [grid] square. */
        private fun IntArray.resample(ink: IntArray, grid: Int): IntArray {
            val left = ink[0]
            val top = ink[1]
            val width = ink[2] - left + 1
            val height = ink[3] - top + 1

            return IntArray(grid * grid) { cell ->
                val column = cell % grid
                val row = cell / grid
                val fromX = left + column * width / grid
                val toX = (left + (column + 1) * width / grid).coerceAtLeast(fromX + 1)
                val fromY = top + row * height / grid
                val toY = (top + (row + 1) * height / grid).coerceAtLeast(fromY + 1)

                var total = 0
                var count = 0
                for (y in fromY until minOf(toY, WORKING)) {
                    for (x in fromX until minOf(toX, WORKING)) {
                        total += this[y * WORKING + x]
                        count++
                    }
                }
                if (count == 0) 0 else total / count
            }
        }
    }
}
