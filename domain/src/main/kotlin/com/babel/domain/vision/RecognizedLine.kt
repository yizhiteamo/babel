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
)

/**
 * How lines are laid out on the page, which decides both how they group and in
 * what order they read.
 *
 * A page can mix both — vertical dialogue alongside horizontal sound effects or
 * notes — so this is a property of a group of lines, never a global setting.
 */
enum class ReadingDirection {
    /** Japanese comics: columns read top to bottom, columns ordered right to left. */
    VERTICAL_RTL,

    /** Western text: lines read left to right, ordered top to bottom. */
    HORIZONTAL_LTR,
}
