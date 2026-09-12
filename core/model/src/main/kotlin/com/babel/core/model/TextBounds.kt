package com.babel.core.model

/**
 * Coordinate space a rectangle is expressed in.
 *
 * Source bounds and rendering coordinates are not interchangeable; conversion is
 * centralized rather than corrected ad hoc at render sites
 * (`docs/systems/overlay.md`).
 */
enum class CoordinateSpace {
    /** Physical screen coordinates, origin at the top-left of the display. */
    SCREEN,

    /** Coordinates relative to the source window. */
    WINDOW,

    /** Coordinates inside the overlay surface that draws translations. */
    RENDER,
}

/** Project-owned rectangle. Deliberately not `android.graphics.Rect`. */
data class TextBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val space: CoordinateSpace,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}
