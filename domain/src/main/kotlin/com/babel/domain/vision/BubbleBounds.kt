package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * Grows a text region outwards to the space that holds it — in practice the
 * inside of a speech bubble.
 *
 * Recognition returns a box hugging the lettering, and for vertical Japanese
 * that box is a narrow column. Laying a horizontal translation into it gives
 * two or three characters a line, which is what the first end-to-end run
 * looked like. The bubble around the text is the space the translation is
 * actually allowed to use, and it is several times wider.
 *
 * Pure arithmetic over a background test, so it can be exercised against a
 * drawn bubble without a frame. Reading pixels is the platform's job.
 */
object BubbleBounds {

    /** Samples taken along an edge to decide whether it is clear. */
    private const val EDGE_SAMPLES = 16

    /**
     * Every sample along an edge must be background before the box may grow
     * over it, and the samples include both ends.
     *
     * A tolerance was tried first, on the theory that a bubble's inside is not
     * perfectly uniform. It let the box's corners sit outside an elliptical
     * bubble — and since the overlay is painted with the bubble's own colour,
     * a corner outside the bubble erases part of the drawn outline. A box one
     * step too small costs nothing; one that crosses the line damages the art.
     *
     * Noise is absorbed by the caller's background test, which compares colours
     * with a tolerance, not here.
     */

    /**
     * Largest share of the frame a box may cover before the search is
     * abandoned, as a fraction expressed in fifths.
     *
     * Measured against the frame, not against the text. A first attempt capped
     * growth at a multiple of the text box and rejected every real bubble: a
     * column of vertical Japanese is narrow, and the balloon around it was
     * seven times wider. Nothing that fills most of the screen is a speech
     * bubble, whereas "several times the text" describes one exactly.
     *
     * Like the frame edge, reaching this is read as "no enclosure" rather than
     * as a limit to stop at. It also bounds the work: without it, a blank page
     * would be probed from one side to the other.
     */
    private const val MAX_FRAME_NUMERATOR = 4
    private const val MAX_FRAME_DENOMINATOR = 5

    /**
     * Returns [start] unchanged when the text turns out not to be inside
     * anything.
     *
     * A speech bubble encloses its text on all four sides, so expansion ends by
     * meeting an outline in every direction. Text on a toolbar or a caption
     * strip is enclosed in one direction only — sideways it runs on until it
     * hits the growth cap or the edge of the frame. Growing those boxes is
     * actively harmful: it turns a small overlay over a browser's tab title
     * into a large one, which is exactly what the first device run of this
     * change produced.
     *
     * So hitting the cap or the frame is read as "there was no enclosure here"
     * and the original box is kept.
     *
     * @param limit the frame; expansion never leaves it.
     * @param isBackground whether the pixel at (x, y) looks like the region's
     *   background — the caller decides what that means, having sampled the
     *   colour.
     */
    fun expand(
        start: TextBounds,
        limit: TextBounds,
        isBackground: (Int, Int) -> Boolean,
    ): TextBounds {
        if (start.width <= 0 || start.height <= 0) return start

        val step = maxOf(2, minOf(start.width, start.height) / 16)
        val maxWidth = limit.width * MAX_FRAME_NUMERATOR / MAX_FRAME_DENOMINATOR
        val maxHeight = limit.height * MAX_FRAME_NUMERATOR / MAX_FRAME_DENOMINATOR

        var left = start.left
        var top = start.top
        var right = start.right
        var bottom = start.bottom

        // One edge at a time, repeatedly, so a box that can only grow sideways
        // still does. Stopping at the first blocked edge would leave a bubble
        // half explored.
        var unbounded = false

        // Sideways first, to exhaustion, and only then up and down.
        //
        // Growing all four at once settles into whatever shape the starting box
        // already had, and the starting box is a tall narrow column of vertical
        // Japanese. Inside a round bubble that equilibrium came back barely half
        // as wide as the bubble allows, because every widening made the top and
        // bottom edges longer and they met the outline sooner.
        //
        // Width is what a horizontal translation actually needs; height beyond
        // the text's own is worth little. So width is spent first.
        var growing = true
        while (growing && !unbounded) {
            growing = false

            if (isColumnClear(left - step, top, bottom, isBackground)) {
                if (left - step < limit.left || right - (left - step) > maxWidth) {
                    unbounded = true
                } else {
                    left -= step
                    growing = true
                }
            }
            if (isColumnClear(right + step, top, bottom, isBackground)) {
                if (right + step > limit.right || (right + step) - left > maxWidth) {
                    unbounded = true
                } else {
                    right += step
                    growing = true
                }
            }
        }

        growing = true
        while (growing && !unbounded) {
            growing = false

            if (isRowClear(top - step, left, right, isBackground)) {
                if (top - step < limit.top || bottom - (top - step) > maxHeight) {
                    unbounded = true
                } else {
                    top -= step
                    growing = true
                }
            }
            if (isRowClear(bottom + step, left, right, isBackground)) {
                if (bottom + step > limit.bottom || (bottom + step) - top > maxHeight) {
                    unbounded = true
                } else {
                    bottom += step
                    growing = true
                }
            }
        }

        if (unbounded) return start

        return TextBounds(left = left, top = top, right = right, bottom = bottom, space = start.space)
    }

    private fun isColumnClear(x: Int, top: Int, bottom: Int, isBackground: (Int, Int) -> Boolean): Boolean {
        val span = bottom - top
        if (span <= 0) return false
        for (i in 0 until EDGE_SAMPLES) {
            val y = top + span * i / (EDGE_SAMPLES - 1)
            if (!isBackground(x, y)) return false
        }
        return true
    }

    private fun isRowClear(y: Int, left: Int, right: Int, isBackground: (Int, Int) -> Boolean): Boolean {
        val span = right - left
        if (span <= 0) return false
        for (i in 0 until EDGE_SAMPLES) {
            val x = left + span * i / (EDGE_SAMPLES - 1)
            if (!isBackground(x, y)) return false
        }
        return true
    }
}
