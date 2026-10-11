package com.babel.core.model

/**
 * Visual properties sampled from the source, when the acquisition method can
 * see them.
 *
 * Only the OCR path can: it holds the actual pixels. Accessibility reports no
 * colours at all, which is why V1 has to guess a background and why its
 * overlays never quite match the app underneath (`docs/decisions/008`).
 *
 * Colours are packed ARGB, as Android represents them. Null means "not known",
 * never "transparent" — a renderer must fall back rather than paint nothing.
 */
data class SourceStyle(
    val backgroundColor: Int? = null,
    val foregroundColor: Int? = null,
    /**
     * How the source text was set. Null where the acquisition method cannot
     * tell, which is every V1 element — accessibility reports no geometry of
     * this kind.
     *
     * A renderer that knows the original ran in vertical columns can set the
     * translation the same way, which is both how manga lettering looks and how
     * a translation comes to cover the text it replaces rather than sitting
     * beside it (`docs/milestones/v2.md`).
     */
    val orientation: TextOrientation? = null,
    /**
     * How large the original lettering was, in pixels of the frame it was read
     * from. Null where the acquisition method cannot see it — every V1 element.
     *
     * A translation set at the size of the text it replaces reads as lettering;
     * one set at a fixed ceiling reads as a caption dropped into the balloon.
     * Both renderers cap type at 24sp, and the reason written there is a V1
     * reason — accessibility can only infer a size from a container node's
     * height, and without a ceiling that becomes display-sized type. The OCR
     * path holds the pixels and can measure instead of inferring, so the
     * ceiling costs it a large balloon's type for nothing: a user reported
     * exactly that on `kr-mag-01`.
     */
    val glyphSizePx: Int? = null,
    /**
     * What is behind the text, row by row, when the acquisition method could
     * see it and it is plain enough to paint back: game mode erases a line on
     * a panel with this and draws its translation in the cleared space
     * (`docs/features/v3-game-translation.md`). Null everywhere else.
     */
    val backdrop: RowGradient? = null,
    /**
     * The colour of the edge drawn round the letters, when the original has
     * one — a game's white type usually carries a dark outline, and set
     * without it on a light patch of art the translation could not be read.
     */
    val outlineColor: Int? = null,
    /**
     * How heavy the original's strokes are: their typical width against the
     * type's height. Null where it was not measured.
     */
    val strokeWeight: Float? = null,
    /** The original was centred on its panel — a choice, a banner — not set from its left. */
    val centred: Boolean = false,
) {
    val isEmpty: Boolean
        get() = backgroundColor == null && foregroundColor == null &&
            orientation == null && glyphSizePx == null && backdrop == null &&
            outlineColor == null && strokeWeight == null

    companion object {
        val UNKNOWN = SourceStyle()
    }
}

/**
 * A backdrop that changes from row to row and evenly along each row — a plain
 * or graded panel, with the text taken out.
 *
 * [left] and [right] hold, per pixel row from the top, the colour just outside
 * the text at either end, packed ARGB. Painting [colorAt] across the row
 * restores the panel: the same model `GameLine.backdropError` measures, so a
 * line is only given one where that error is low.
 *
 * The gradient covers the text's bounds, [marginPx] beyond them at either
 * end and [rowMarginPx] above and below. They differ: up and down is room a
 * line of type needs, while along the line a wide margin reaches into the
 * next piece — a name's patch took the colour of the affiliation beside it.
 */
data class RowGradient(
    val left: List<Int>,
    val right: List<Int>,
    val marginPx: Int = 0,
    val rowMarginPx: Int = marginPx,
) {
    init {
        require(left.size == right.size) { "one colour per row at each end" }
    }

    val rows: Int get() = left.size

    /** The colour [fraction] (0..1) of the way along [row], from left to right. */
    fun colorAt(row: Int, fraction: Float): Int {
        val r = row.coerceIn(0, rows - 1)
        val t = fraction.coerceIn(0f, 1f)
        val a = left[r]
        val b = right[r]
        fun channel(shift: Int): Int {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t + 0.5f).toInt().coerceIn(0, 255)
        }
        return (channel(24) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
