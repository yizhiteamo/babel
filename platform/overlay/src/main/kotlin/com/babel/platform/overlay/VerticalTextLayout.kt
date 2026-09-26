package com.babel.platform.overlay

/**
 * Works out how to set a translation in vertical columns, the way the Japanese
 * it replaces was set.
 *
 * Two reasons this is worth doing rather than laying everything out
 * horizontally. It is how manga lettering looks — a horizontal line dropped
 * into an upright balloon reads as a caption pasted over the art. And it is how
 * the translation comes to *cover* the text it replaces: the original occupies
 * three or four upright columns, so a translation set the same way lands on top
 * of them instead of beside them. On real pages that was the difference between
 * one readable text and two overlapping ones (`docs/milestones/v2.md`).
 *
 * Pure arithmetic, for the same reason [TextFitting] is: this is where the
 * layout bugs will be, and a `Canvas` cannot be had in a JVM unit test.
 * Everything is in pixels.
 */
internal object VerticalTextLayout {

    /**
     * Gap between columns as a fraction of the glyph size. Columns set solid
     * are hard to follow; a full glyph of air between them is more than manga
     * uses.
     */
    /**
     * The type sizes vertical setting may use, and the padding inside the
     * balloon. Here rather than in the view because [fits] has to answer the
     * same question the view will, and two copies of a constant is how they
     * come to disagree.
     */
    const val MAX_GLYPH_SP = 24f
    const val MIN_GLYPH_SP = 8f
    const val HORIZONTAL_PADDING_PX = 4
    const val VERTICAL_PADDING_PX = 4

    private const val COLUMN_GAP_RATIO = 0.25f

    /** Vertical advance per character, as a fraction of the glyph size. */
    private const val LINE_ADVANCE_RATIO = 1.0f

    /**
     * The text broken into columns at a size that fits, or null when even the
     * smallest size will not fit and the caller should lay out horizontally.
     *
     * @param columns in the order they are written, first column first. The
     *   first column is drawn **rightmost** — CJK columns run right to left.
     */
    data class Result(val glyphSizePx: Int, val columns: List<String>) {
        val columnPitchPx: Int get() = (glyphSizePx * (1f + COLUMN_GAP_RATIO)).toInt()
        val advancePx: Int get() = (glyphSizePx * LINE_ADVANCE_RATIO).toInt()
    }

    fun layout(
        text: String,
        boxWidthPx: Int,
        boxHeightPx: Int,
        maxGlyphPx: Int,
        minGlyphPx: Int,
    ): Result? {
        val content = text.filterNot { it == '\n' || it == '\r' }
        if (content.isEmpty() || boxWidthPx <= 0 || boxHeightPx <= 0) return null
        if (minGlyphPx <= 0 || maxGlyphPx < minGlyphPx) return null

        // Largest first: the biggest size that fits is the one that fills the
        // bubble, and stepping down from the top means never settling for
        // smaller type than the space allows.
        for (glyph in maxGlyphPx downTo minGlyphPx) {
            val columns = split(content, boxHeightPx, glyph) ?: continue
            val pitch = (glyph * (1f + COLUMN_GAP_RATIO)).toInt().coerceAtLeast(1)
            if (columns.size * pitch <= boxWidthPx) {
                return Result(glyph, columns)
            }
        }
        return null
    }

    /** Null when not even one character fits the height at this size. */
    private fun split(content: String, boxHeightPx: Int, glyphPx: Int): List<String>? {
        val advance = (glyphPx * LINE_ADVANCE_RATIO).toInt().coerceAtLeast(1)
        val perColumn = boxHeightPx / advance
        if (perColumn < 1) return null

        return content.chunked(perColumn)
    }

    /**
     * Whether vertical setting can show this at all, in a box of this size.
     *
     * Asked **before** the view is chosen, because a balloon that cannot take
     * vertical text is better set horizontally than left blank — which is what
     * used to happen, and what a user reported: the overlay paints its sampled
     * background whatever happens, so a null layout covered the artwork and
     * showed nothing. The reader lost the translation *and* the original.
     *
     * Vertical setting runs out of room sooner than horizontal does, and the
     * arithmetic says why: a column costs `1 + COLUMN_GAP_RATIO` of the glyph
     * in width, so a quarter of the box goes to the gaps between columns.
     * Measured on a 560dpi phone, where `MIN_GLYPH_SP` is 28px: **six**
     * characters would not fit a 68x164 balloon vertically, while the same box
     * takes far more horizontally.
     */
    fun fits(text: String, boxWidthPx: Int, boxHeightPx: Int, density: Float): Boolean =
        layout(
            text = text,
            boxWidthPx = boxWidthPx - HORIZONTAL_PADDING_PX * 2,
            boxHeightPx = boxHeightPx - VERTICAL_PADDING_PX * 2,
            maxGlyphPx = (MAX_GLYPH_SP * density).toInt(),
            minGlyphPx = (MIN_GLYPH_SP * density).toInt(),
        ) != null
}
