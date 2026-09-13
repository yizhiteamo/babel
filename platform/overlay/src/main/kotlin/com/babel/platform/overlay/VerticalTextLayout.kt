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
}
