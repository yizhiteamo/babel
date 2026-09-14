package com.babel.domain.vision

/**
 * Decides whether a new frame is worth recognising.
 *
 * A page under a reader is static, so most frames are the page again. Running
 * OCR on each of them is not merely wasted battery — it actively destroys the
 * result. Recognition is not deterministic: on this emulator, thirteen scans of
 * one unchanged page produced line counts of 26, 27, 29 and 30. Every scan
 * therefore produced different element ids, so the previous translations were
 * dropped and re-requested, and the screen never held more than one at a time.
 *
 * Scanning once per page fixes that by never asking the question twice.
 *
 * The complication is that the captured screen includes our own overlays: a
 * translation appearing is itself a change. `FLAG_SECURE` looks like the
 * platform answer — a secure window is documented not to render on a non-secure
 * display — but measured on device it blanks the *whole* mirror, taking the
 * user's screenshots with it. So overlays are separated from page turns by size
 * instead: an overlay covers a few percent of the screen, a page turn nearly all
 * of it, and the gap between those is wide enough to sit a threshold in.
 */
object FrameChangeDetector {

    /**
     * A cell counts as different at more than this much luminance apart, out of
     * 255. Below it is capture noise rather than content.
     */
    private const val CELL_TOLERANCE = 12

    /**
     * Share of cells that must differ before the page is treated as new.
     *
     * Sized from the two things being told apart, not tuned: translations
     * covering the text of a page come to roughly a tenth of the screen, while
     * turning a page changes nearly all of it.
     */
    private const val CHANGED_FRACTION = 0.30

    /**
     * Share of cells that may differ from the previous tick and still count as
     * a screen that has stopped moving.
     *
     * Well above capture noise — an unchanged page moves no cells at all — and
     * far below a page in transition, which moves most of them. It is not the
     * inverse of [CHANGED_FRACTION]: the gap between the two is deliberate, so
     * that a frame is neither read while it is still arriving nor rejected for
     * a cursor blink.
     */
    private const val SETTLED_FRACTION = 0.05

    /**
     * @param previous signature of the last recognised frame, or null for the
     *   first frame of a session — which is always worth recognising.
     * @param current signature of the frame just captured.
     */
    fun shouldRecognize(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size || current.isEmpty()) return true
        return differingFraction(previous, current) >= CHANGED_FRACTION
    }

    /**
     * Whether the screen has stopped moving since the frame before it.
     *
     * Separate from [shouldRecognize], and asked of a different pair: that one
     * compares against the last frame **recognised**, this one against the last
     * frame **seen**. A page can differ from what was read while still being
     * half-drawn.
     *
     * It is worth asking because the tree updates before the pixels do.
     * Measured on a device: opening a comic straight after an article, the
     * accessibility tree described the comic while Chromium was still painting
     * the article, and the capture read the outgoing page — putting two black
     * boxes of the article's text across the artwork. The same happens on a
     * page turn inside a reader, mid-animation.
     *
     * @param previous signature of the frame seen on the last tick, or null
     *   when there was none — in which case nothing has settled yet and the
     *   answer is no, at a cost of one tick.
     */
    fun hasSettled(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size || current.isEmpty()) return false
        return differingFraction(previous, current) < SETTLED_FRACTION
    }

    private fun differingFraction(previous: IntArray, current: IntArray): Double {
        var differing = 0
        for (index in current.indices) {
            if (kotlin.math.abs(current[index] - previous[index]) > CELL_TOLERANCE) differing++
        }
        return differing.toDouble() / current.size
    }
}
