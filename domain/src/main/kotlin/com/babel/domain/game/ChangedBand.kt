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
    /**
     * [top] to [bottom] grown until no line of the last reading is cut by
     * either edge, with [margin] to spare. A band through the middle of a
     * line read neither half: measured, a blinking arrow beside the dialogue
     * made a band 199 px tall that cut the first of two lines, which then
     * dropped out of every reading and was left untranslated.
     */
    fun widened(previous: List<GameLine>, top: Int, bottom: Int, margin: Int, frameHeight: Int): Pair<Int, Int> {
        var t = top
        var b = bottom
        do {
            val before = t to b
            for (line in previous) {
                val cut = line.bounds.top < b && line.bounds.bottom > t
                if (!cut) continue
                if (line.bounds.top < t + margin) t = minOf(t, line.bounds.top - margin)
                if (line.bounds.bottom > b - margin) b = maxOf(b, line.bounds.bottom + margin)
            }
        } while ((t to b) != before)
        return t.coerceAtLeast(0) to b.coerceAtMost(frameHeight)
    }

    /** A part of the grid that changed: its columns and its rows. */
    data class Region(val columns: IntRange, val rows: IntRange)

    /**
     * The changed cells as separate regions, one per run of changed columns
     * with [gap] or more unchanged columns between runs. A chat beside the
     * home screen's animated art changed at both ends of the frame and in
     * the middle; as one box, the whole frame was read every time.
     */
    fun regions(before: IntArray?, after: IntArray, columns: Int, gap: Int): List<Region> {
        if (before == null || before.size != after.size) return emptyList()
        val rows = after.size / columns
        val changedRows = Array(columns) { mutableListOf<Int>() }
        for (i in after.indices) if (abs(after[i] - before[i]) >= CELL_THRESHOLD) changedRows[i % columns] += i / columns
        val out = mutableListOf<Region>()
        var c = 0
        while (c < columns) {
            if (changedRows[c].isEmpty()) { c++; continue }
            val start = c
            var end = c
            var quiet = 0
            c++
            while (c < columns && quiet < gap) {
                if (changedRows[c].isEmpty()) quiet++ else { end = c; quiet = 0 }
                c++
            }
            val rowsHere = (start..end).flatMap { changedRows[it] }
            out += Region(start..end, (rowsHere.minOrNull() ?: 0)..(rowsHere.maxOrNull() ?: (rows - 1)))
        }
        return out
    }

    /** Columns [first, last] of grid cells that changed; null when nothing did, or before there is a grid. */
    fun columnsOf(before: IntArray?, after: IntArray, columns: Int): IntRange? {
        if (before == null || before.size != after.size) return null
        var first = -1
        var last = -1
        for (i in after.indices) {
            if (abs(after[i] - before[i]) >= CELL_THRESHOLD) {
                val column = i % columns
                if (first < 0 || column < first) first = column
                if (column > last) last = column
            }
        }
        return if (first < 0) null else first..last
    }

    /**
     * [left] to [right] grown until no line of the last reading within rows
     * [top, bottom) is cut by either side — a box through a line reads
     * neither half — with [margin] to spare.
     */
    fun widenedAcross(previous: List<GameLine>, left: Int, right: Int, top: Int, bottom: Int, margin: Int, frameWidth: Int): Pair<Int, Int> {
        var l = left
        var r = right
        do {
            val before = l to r
            for (line in previous) {
                if (line.bounds.bottom <= top || line.bounds.top >= bottom) continue
                val cut = line.bounds.left < r && line.bounds.right > l
                if (!cut) continue
                if (line.bounds.left < l + margin) l = minOf(l, line.bounds.left - margin)
                if (line.bounds.right > r - margin) r = maxOf(r, line.bounds.right + margin)
            }
        } while ((l to r) != before)
        return l.coerceAtLeast(0) to r.coerceAtMost(frameWidth)
    }

    /**
     * The box [left, top, right, bottom] grown until no line of the last
     * reading that it overlaps is cut by any side, with margins to spare.
     * Only lines it overlaps: grown by every line on its rows across the
     * screen, a chat's box took in the list beside it and then the frame.
     */
    fun widenedBox(
        previous: List<GameLine>,
        left: Int, top: Int, right: Int, bottom: Int,
        marginX: Int, marginY: Int, frameWidth: Int, frameHeight: Int,
    ): IntArray {
        var l = left
        var t = top
        var r = right
        var b = bottom
        do {
            val before = listOf(l, t, r, b)
            for (line in previous) {
                val x = line.bounds
                val overlaps = x.left < r && x.right > l && x.top < b && x.bottom > t
                if (!overlaps) continue
                if (x.left < l + marginX) l = minOf(l, x.left - marginX)
                if (x.right > r - marginX) r = maxOf(r, x.right + marginX)
                if (x.top < t + marginY) t = minOf(t, x.top - marginY)
                if (x.bottom > b - marginY) b = maxOf(b, x.bottom + marginY)
            }
            l = l.coerceAtLeast(0); t = t.coerceAtLeast(0); r = r.coerceAtMost(frameWidth); b = b.coerceAtMost(frameHeight)
        } while (listOf(l, t, r, b) != before)
        return intArrayOf(l, t, r, b)
    }

    /** As [merge], for a box: the new reading inside it, the previous one wholly outside it. */
    fun mergeBox(previous: List<GameLine>, inBox: List<GameLine>, left: Int, top: Int, right: Int, bottom: Int): List<GameLine> =
        previous.filter {
            it.bounds.bottom <= top || it.bounds.top >= bottom || it.bounds.right <= left || it.bounds.left >= right
        } + inBox

    fun merge(previous: List<GameLine>, inBand: List<GameLine>, top: Int, bottom: Int): List<GameLine> =
        previous.filter { it.bounds.bottom <= top || it.bounds.top >= bottom } + inBand
}
