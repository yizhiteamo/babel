package com.babel.domain.game

import com.babel.core.model.TextBounds
import com.babel.domain.game.GamePixels.luma
import kotlin.math.abs

/** A downsampled brightness copy of a game window frame; see [GameFrameLook.smallOf]. */
class SmallFrame(val width: Int, val height: Int, val luma: IntArray, val scale: Int)

/**
 * Coarse and local brightness grids, scroll shift recovery, front-layer ink
 * filtering, and dimmer/contrast checks over [FramePixels].
 */
object GameFrameLook {

    const val SAMPLE_STEP = 4

    /** A line's look: this grid, and how far (0..255) any cell may change and be the same. */
    const val LOOK_COLUMNS = 24
    const val LOOK_ROWS = 4
    const val REPLACED_LOOK_LUMA = 16.0
    const val MAX_TRACK_LINES = 4
    const val SHIFT_TRIES = 3
    const val TRACK_STEP_PX = 4
    const val STILL_LOOK_LUMA = 12.0

    /** A line with this many times less ink than one over it is behind it. */
    const val BEHIND_RATIO = 3

    /** Ink, per thousand samples, that means a line is still there; a line of type has 50-200. */
    const val MIN_INK_PER_MILLE = 8

    /** Ink against its panel at least this far apart (0..255) is in full view; dimmed, it measured 22–55, bright 150 and more. */
    const val ACTIVE_CONTRAST = 100.0

    /** A line whose brightest is under this is behind a dimmer: dimmed white measured 44–83. */
    const val DIMMED_BRIGHTEST = 120.0

    /** Brightness apart from the column's middle value that counts as ink. */
    const val INK_CONTRAST = 70.0

    /** About 50 px a cell on a 3200x1440 window: a dialogue line is a row or two. */
    const val GRID_COLUMNS = 64
    const val GRID_ROWS = 29

    /** Mean brightness change per grid cell, 0..255, that is a new scene. */
    const val SCENE_CHANGE_LUMA = 30.0

    /** The small copy of each picture, at this fraction, and how far its line may move and be still. */
    const val SMALL_SCALE = 4
    const val STILL_SMALL_LUMA = 2.0

    fun smallOf(frame: FramePixels): SmallFrame {
        val w = (frame.width / SMALL_SCALE).coerceAtLeast(1)
        val h = (frame.height / SMALL_SCALE).coerceAtLeast(1)
        val row = IntArray(frame.width)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            frame.getPixels(row, 0, frame.width, 0, y * SMALL_SCALE, frame.width, 1)
            for (x in 0 until w) out[y * w + x] = luma(row[x * SMALL_SCALE]).toInt()
        }
        return SmallFrame(w, h, out, SMALL_SCALE)
    }

    /** Whether [box] looks the same in two pictures a moment apart. */
    fun stillBetween(before: SmallFrame?, now: SmallFrame, box: TextBounds): Boolean {
        if (before == null || before.width != now.width || before.height != now.height) return false
        val l = (box.left / now.scale).coerceIn(0, now.width - 1)
        val r = (box.right / now.scale).coerceIn(l + 1, now.width)
        val t = (box.top / now.scale).coerceIn(0, now.height - 1)
        val b = (box.bottom / now.scale).coerceIn(t + 1, now.height)
        var sum = 0L
        var n = 0
        for (y in t until b) for (x in l until r) {
            val i = y * now.width + x
            sum += abs(before.luma[i] - now.luma[i])
            n++
        }
        return n > 0 && sum.toDouble() / n <= STILL_SMALL_LUMA
    }

    /**
     * Whether every piece of a box, [piece] px wide, stood still between two
     * pictures. A few letters typed at the end of a wide box change too little
     * of it to move the mean over the whole: a dialogue box 2500 px wide read
     * as still while its line was typing out.
     */
    fun stillPieceByPiece(before: SmallFrame, now: SmallFrame, box: TextBounds, piece: Int): Boolean {
        val step = piece.coerceAtLeast(now.scale)
        var left = box.left
        while (left < box.right) {
            val right = minOf(box.right, left + step)
            if (!stillBetween(before, now, TextBounds(left, box.top, right, box.bottom, box.space))) return false
            left = right
        }
        return true
    }

    /** Mean brightness of a [GRID_COLUMNS] x [GRID_ROWS] scaled pixel array, for [ChangedBand]. */
    fun lumaGridOf(pixels: IntArray): IntArray =
        IntArray(pixels.size) { luma(pixels[it]).toInt() }

    /** Whether the whole picture moved far enough since [lastGrid] to be a new scene. */
    fun sceneChanged(lastGrid: IntArray?, grid: IntArray): Boolean =
        lastGrid?.let { before ->
            grid.indices.sumOf { abs(grid[it] - before[it]) }.toDouble() / grid.size > SCENE_CHANGE_LUMA
        } ?: false

    /**
     * Whether [box] still holds ink on this frame: samples far from their
     * row's middle value. A blank panel, plain or graded, has none.
     */
    fun inked(frame: FramePixels, box: TextBounds): Boolean = inkPerMille(frame, box) >= MIN_INK_PER_MILLE

    /** The line's box as a small grid of mean brightness. */
    fun lookOf(frame: FramePixels, box: TextBounds): IntArray? {
        val left = box.left.coerceIn(0, frame.width - 1)
        val right = box.right.coerceIn(left + 1, frame.width)
        val top = box.top.coerceIn(0, frame.height - 1)
        val bottom = box.bottom.coerceIn(top + 1, frame.height)
        val w = right - left
        val h = bottom - top
        if (w < LOOK_COLUMNS || h < LOOK_ROWS) return null
        val row = IntArray(w)
        val sums = IntArray(LOOK_COLUMNS * LOOK_ROWS)
        val counts = IntArray(LOOK_COLUMNS * LOOK_ROWS)
        var y = top
        while (y < bottom) {
            frame.getPixels(row, 0, w, left, y, w, 1)
            val r = (y - top) * LOOK_ROWS / h
            var x = 0
            while (x < w) {
                val c = x * LOOK_COLUMNS / w
                sums[r * LOOK_COLUMNS + c] += luma(row[x]).toInt()
                counts[r * LOOK_COLUMNS + c]++
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return IntArray(sums.size) { if (counts[it] == 0) 0 else sums[it] / counts[it] }
    }

    /**
     * Whether the place a published line stood in now shows something else:
     * on average far from what it showed then. A recogniser's miss on a still
     * line leaves its pixels alone; moving art behind a translucent box moves
     * a few cells, not the mean of all of them.
     */
    fun replacedSince(
        frame: FramePixels,
        publishedLooks: Map<String, Pair<TextBounds, IntArray>>,
        box: TextBounds,
    ): Boolean {
        val then = publishedLooks.values.firstOrNull { it.first == box }?.second ?: return false
        val now = lookOf(frame, box) ?: return false
        return replaced(then, now)
    }

    /**
     * Mean difference of the two looks with each one's own mean taken out:
     * what moved, not what was shaded. A chat list's selected row darkens
     * evenly under its words, which stay; a scrolled bubble's words go.
     */
    fun replaced(then: IntArray, now: IntArray): Boolean = lookDifference(then, now) > REPLACED_LOOK_LUMA

    fun lookDifference(then: IntArray, now: IntArray): Double {
        if (then.size != now.size) return Double.MAX_VALUE
        val a = then.average()
        val b = now.average()
        return then.indices.sumOf { abs((then[it] - a) - (now[it] - b)) } / then.size
    }

    /**
     * One vertical shift that finds every one of [keys]'s looks again, or
     * null. Scrolled, a chat's lines move together; a line replaced by other
     * words matches at no shift.
     */
    fun sharedShift(
        frame: FramePixels,
        publishedLooks: Map<String, Pair<TextBounds, IntArray>>,
        keys: List<String>,
    ): Int? {
        // The first of a few lines, top down, that is found again: one
        // replaced by other words, or gone past the edge, is found nowhere.
        for (look in keys.mapNotNull { publishedLooks[it] }.sortedBy { it.first.top }.take(SHIFT_TRIES)) {
            shiftOf(frame, look.first, look.second)?.let { return it }
        }
        return null
    }

    fun shiftOf(frame: FramePixels, b: TextBounds, then: IntArray): Int? {
        fun score(d: Int): Double? {
            val now = lookOf(frame, TextBounds(b.left, b.top + d, b.right, b.bottom + d, b.space)) ?: return null
            return now.indices.sumOf { abs(now[it] - then[it]) }.toDouble() / now.size
        }
        val reach = b.height * MAX_TRACK_LINES
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        var dy = -reach
        while (dy <= reach) {
            if (dy != 0) score(dy)?.let { if (it < bestScore) { bestScore = it; best = dy } }
            dy += TRACK_STEP_PX
        }
        val coarse = best ?: return null
        var fine = coarse
        var fineScore = bestScore
        for (d in coarse - TRACK_STEP_PX + 1 until coarse + TRACK_STEP_PX) {
            if (d == 0) continue
            score(d)?.let { if (it < fineScore) { fineScore = it; fine = d } }
        }
        return if (fineScore <= STILL_LOOK_LUMA) fine else null
    }

    /** Whether the line at [key] is found again shifted by [dy]. */
    fun foundAt(
        frame: FramePixels,
        publishedLooks: Map<String, Pair<TextBounds, IntArray>>,
        key: String,
        dy: Int,
    ): Boolean {
        val (b, then) = publishedLooks[key] ?: return false
        val now = lookOf(frame, TextBounds(b.left, b.top + dy, b.right, b.bottom + dy, b.space)) ?: return false
        return now.indices.sumOf { abs(now[it] - then[it]) }.toDouble() / now.size <= STILL_LOOK_LUMA
    }

    /** The largest change of any cell: one new glyph in a long line moves the mean by little. */
    fun largestDifference(a: IntArray, b: IntArray): Double =
        if (a.size != b.size) Double.MAX_VALUE else a.indices.maxOf { abs(a[it] - b[it]) }.toDouble()

    /**
     * Of lines read over each other, the one in front: a history screen is
     * drawn translucent over the game's dialogue box, the recogniser read
     * now the history and now the dimmed dialogue behind it, and each
     * withdrew the other twice a second. The one behind has a fraction of the
     * other's ink (measured 37–57 per thousand against 264).
     */
    fun frontLayer(frame: FramePixels, lines: List<GameLine>): List<GameLine> {
        if (lines.size < 2) return lines
        val ink = lines.associateWith { inkPerMille(frame, it.bounds) }
        return lines.filter { line ->
            lines.none { other ->
                other !== line && covers(other.bounds, line.bounds) && ink.getValue(line) * BEHIND_RATIO < ink.getValue(other)
            }
        }
    }

    fun inkPerMille(frame: FramePixels, box: TextBounds): Int {
        val left = box.left.coerceIn(0, frame.width - 1)
        val right = box.right.coerceIn(left + 1, frame.width)
        val w = right - left
        val row = IntArray(w)
        var ink = 0
        var samples = 0
        var y = box.top.coerceAtLeast(0)
        while (y < box.bottom.coerceAtMost(frame.height)) {
            frame.getPixels(row, 0, w, left, y, w, 1)
            val lumas = DoubleArray((w + SAMPLE_STEP - 1) / SAMPLE_STEP) { luma(row[it * SAMPLE_STEP]) }
            val middle = lumas.sortedArray()[lumas.size / 2]
            for (l in lumas) if (abs(l - middle) >= INK_CONTRAST) ink++
            samples += lumas.size
            y += SAMPLE_STEP
        }
        return if (samples == 0) 0 else ink * 1000 / samples
    }

    /** A third of the smaller box under the other: layers are offset by half a line. */
    fun covers(a: TextBounds, b: TextBounds): Boolean {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return w > 0 && h > 0 && w.toLong() * h * 3 >= minOf(a.width.toLong() * a.height, b.width.toLong() * b.height)
    }

    /** Whether nothing in [box] is bright: a dimmer over it. */
    fun dimmed(frame: FramePixels, box: TextBounds): Boolean = brightestOf(frame, box) < DIMMED_BRIGHTEST

    fun brightestOf(frame: FramePixels, box: TextBounds): Double {
        val l = box.left.coerceIn(0, frame.width - 1)
        val r = box.right.coerceIn(l + 1, frame.width)
        val t = box.top.coerceIn(0, frame.height - 1)
        val b = box.bottom.coerceIn(t + 1, frame.height)
        val row = IntArray(r - l)
        val lumas = ArrayList<Double>()
        for (y in t until b step 2) {
            frame.getPixels(row, 0, row.size, l, y, row.size, 1)
            for (x in row.indices step 2) lumas += luma(row[x])
        }
        lumas.sort()
        return lumas[(lumas.size * 95) / 100]
    }

    /** The spread of brightness in [box], ink to panel: low where a dimmer lies over the line. */
    fun contrastOf(frame: FramePixels, box: TextBounds): Double {
        val l = box.left.coerceIn(0, frame.width - 1)
        val r = box.right.coerceIn(l + 1, frame.width)
        val t = box.top.coerceIn(0, frame.height - 1)
        val b = box.bottom.coerceIn(t + 1, frame.height)
        val row = IntArray(r - l)
        val lumas = ArrayList<Double>()
        for (y in t until b step 2) {
            frame.getPixels(row, 0, row.size, l, y, row.size, 1)
            for (x in row.indices step 2) lumas += luma(row[x])
        }
        lumas.sort()
        return lumas[(lumas.size * 95) / 100] - lumas[lumas.size / 20]
    }
}
