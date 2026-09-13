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
     * @param previous signature of the last recognised frame, or null for the
     *   first frame of a session — which is always worth recognising.
     * @param current signature of the frame just captured.
     */
    fun shouldRecognize(previous: IntArray?, current: IntArray): Boolean {
        if (previous == null || previous.size != current.size || current.isEmpty()) return true

        var differing = 0
        for (index in current.indices) {
            if (kotlin.math.abs(current[index] - previous[index]) > CELL_TOLERANCE) differing++
        }

        return differing.toDouble() / current.size >= CHANGED_FRACTION
    }
}
