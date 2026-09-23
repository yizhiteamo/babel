package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * The order a reader takes the balloons on a page.
 *
 * The pipeline had none. `DetectingPageReader` handed balloons over in whatever
 * order the model emitted them, which is invisible while each balloon is its own
 * sentence and is exactly what breaks when one sentence spans several: joining a
 * balloon to "the next one" means nothing without an order
 * (`docs/milestones/v2.md`).
 *
 * Japanese pages read **right to left, top to bottom**. Rows come first — a
 * balloon slightly lower but far to the right is still read first — so
 * positions are banded before they are compared. Without the band a two-pixel
 * difference between two box tops reorders a row, and the order is the whole
 * point.
 *
 * Right-to-left is not a safe default for every comic, and this is only reached
 * from the manga path, where the recogniser is Japanese and the assumption
 * holds. A western comic would want the mirror of it, and that belongs with
 * whatever eventually decides the page's language rather than here.
 */
object MangaReadingOrder {

    /** Two balloons within this fraction of the page's height are one row. */
    private const val ROW_FRACTION = 0.08f

    /**
     * @param pageHeight the height of the frame the bounds were measured in,
     *   which sets how tall a row is.
     */
    fun of(pageHeight: Int): Comparator<TextBounds> {
        val band = (pageHeight * ROW_FRACTION).toInt().coerceAtLeast(1)
        return compareBy<TextBounds> { it.top / band }.thenByDescending { it.right }
    }
}
