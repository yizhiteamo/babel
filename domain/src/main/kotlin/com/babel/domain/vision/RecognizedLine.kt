package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * One line of text as an OCR engine reported it, in project-owned terms.
 *
 * For vertical Japanese a "line" is a column. Engine types stay out of the
 * domain for the same reason provider types do (ADR 005): the pipeline must not
 * change shape when the recogniser is swapped.
 */
data class RecognizedLine(
    val text: String,
    val bounds: TextBounds,
    /**
     * What the engine said about how this line is set, or null if it said
     * nothing.
     *
     * Kept as engine input rather than derived here so that a recogniser which
     * knows the answer is believed over any inference of ours. ML Kit reports a
     * line angle and is authoritative; [TextRegionGrouper] falls back to shape
     * only when this is null.
     */
    val orientation: TextOrientation? = null,
)
