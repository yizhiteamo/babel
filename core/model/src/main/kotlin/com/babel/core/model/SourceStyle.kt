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
) {
    val isEmpty: Boolean
        get() = backgroundColor == null && foregroundColor == null &&
            orientation == null && glyphSizePx == null

    companion object {
        val UNKNOWN = SourceStyle()
    }
}
