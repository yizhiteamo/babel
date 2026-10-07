package com.babel.domain.game

import kotlin.math.abs

/**
 * Which horizontal band of a game's frame changed since the last one read.
 *
 * A dialogue line changing touches the dialogue box and nothing else, yet the
 * whole frame was read every time: 160–360 ms of recognition, measured, most
 * of it on pixels that had not moved (`docs/milestones/v3.md`). Comparing a
 * small brightness grid finds the rows that did move, so only that band is
 * read again and the lines elsewhere are kept.
 *
 * A band, not a box: game text is set in horizontal lines, and a box cut to
 * the changed cells would cut a line that grew at its end.
 */
object ChangedBand {

    /** Rows [first, last] of grid cells that changed; null when nothing did. */
    data class Rows(val first: Int, val last: Int)

    /** Mean brightness difference, out of 255, that counts a cell as changed. */
    const val CELL_THRESHOLD = 10

    fun of(before: IntArray?, after: IntArray, columns: Int): Rows? {
        if (before == null || before.size != after.size) return Rows(0, after.size / columns - 1)
        var first = -1
        var last = -1
        for (i in after.indices) {
            if (abs(after[i] - before[i]) >= CELL_THRESHOLD) {
                val row = i / columns
                if (first < 0) first = row
                last = row
            }
        }
        return if (first < 0) null else Rows(first, last)
    }

    /**
     * The lines of a frame read only in its band [top, bottom): the new
     * reading inside the band, the previous one outside it. A previous line
     * that reaches into the band is dropped, because the new reading holds
     * whatever is there now.
     */
    fun merge(previous: List<GameLine>, inBand: List<GameLine>, top: Int, bottom: Int): List<GameLine> =
        previous.filter { it.bounds.bottom <= top || it.bounds.top >= bottom } + inBand
}
