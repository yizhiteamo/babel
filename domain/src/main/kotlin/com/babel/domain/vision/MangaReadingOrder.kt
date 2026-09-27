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
 * Rows come first in either direction — a balloon slightly lower but far to the
 * side is still read first — so positions are banded before they are compared.
 * Without the band a two-pixel difference between two box tops reorders a row,
 * and the order is the whole point.
 *
 * **Which way a row runs is the caller's to say.** Japanese pages read right to
 * left; Korean webtoons set their text horizontally and read left to right like
 * a western comic. This used to hard-code right-to-left and note that the
 * choice "belongs with whatever eventually decides the page's language" — that
 * decision now exists (`BubbleRecognizer.pageIsKorean`, from the whole-frame
 * probe that already runs before any balloon is read), so the note became a
 * parameter.
 *
 * No default: there is one call site, and a default is how the Japanese
 * assumption would quietly come back.
 */
object MangaReadingOrder {

    /** Two balloons within this fraction of the page's height are one row. */
    private const val ROW_FRACTION = 0.08f

    /**
     * @param pageHeight the height of the frame the bounds were measured in,
     *   which sets how tall a row is.
     * @param rightToLeft true for Japanese artwork, false for a page that reads
     *   the western way.
     */
    fun of(pageHeight: Int, rightToLeft: Boolean): Comparator<TextBounds> {
        val band = (pageHeight * ROW_FRACTION).toInt().coerceAtLeast(1)
        val row = compareBy<TextBounds> { it.top / band }
        return if (rightToLeft) {
            row.thenByDescending { it.right }
        } else {
            row.thenBy { it.left }
        }
    }
}
