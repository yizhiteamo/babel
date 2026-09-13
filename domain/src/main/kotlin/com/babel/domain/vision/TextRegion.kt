package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * Lines that belong together — in practice one speech bubble — with their
 * reading order already resolved.
 *
 * This is the unit that gets translated. An OCR text block is not: a recogniser
 * may split one bubble across several blocks, or merge two columns into one
 * block in the wrong order (both observed, see `docs/milestones/v2.md`).
 */
data class TextRegion(
    /** In reading order, so joining them yields readable text. */
    val lines: List<RecognizedLine>,
    /** Bounding box covering every line, used to place and erase. */
    val bounds: TextBounds,
) {
    /**
     * Joined without separators: Japanese columns continue one sentence, so a
     * space between them would be wrong.
     */
    val text: String get() = lines.joinToString(separator = "") { it.text }
}
