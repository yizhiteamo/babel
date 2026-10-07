package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * Drops a detection that is part of another one.
 *
 * The detector can report one block of lettering twice: once whole, and once
 * as a piece of it. Measured on a pixiv character sheet: a name line, 267x81,
 * reported as well as the 308x249 block that holds it and its description.
 * Both were read and translated, and the two translations were drawn on top of
 * each other — `Territories` leaves boxes alone when they overlap by more than
 * half, because there is no sensible line to cut between them.
 *
 * The larger box is kept: it holds everything the smaller one does, and
 * reading it whole keeps a sentence together.
 */
object NestedDetections {

    /** How much of the smaller box must lie inside the larger. */
    const val CONTAINED_FRACTION = 0.7

    fun <T> dropNested(items: List<T>, boxOf: (T) -> TextBounds): List<T> =
        items.filterIndexed { index, item ->
            val box = boxOf(item)
            items.indices.none { other ->
                other != index && contains(boxOf(items[other]), box, larger = other < index)
            }
        }

    /**
     * Whether [outer] holds most of [inner] and is bigger than it. Equal areas
     * are decided by [larger], so of two identical boxes exactly one survives.
     */
    private fun contains(outer: TextBounds, inner: TextBounds, larger: Boolean): Boolean {
        val outerArea = outer.width.toLong() * outer.height
        val innerArea = inner.width.toLong() * inner.height
        if (outerArea < innerArea || (outerArea == innerArea && !larger)) return false
        val w = minOf(outer.right, inner.right) - maxOf(outer.left, inner.left)
        val h = minOf(outer.bottom, inner.bottom) - maxOf(outer.top, inner.top)
        if (w <= 0 || h <= 0 || innerArea == 0L) return false
        return w.toLong() * h >= innerArea * CONTAINED_FRACTION
    }
}
