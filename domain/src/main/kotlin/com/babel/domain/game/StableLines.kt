package com.babel.domain.game

import com.babel.core.model.TextBounds
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Follows lines from frame to frame and says which have settled.
 *
 * A line is settled when two consecutive frames read **the same text in the
 * same place**. That one rule does three jobs at once:
 *
 * - a typewriter line is still growing, so it reads differently each frame and
 *   is not translated half-written;
 * - an animated backdrop moves the pixels without changing the text, so it
 *   does not hold a line back the way a whole-frame "has the picture
 *   stopped?" test would;
 * - a misread usually differs between two readings (a dropped character, a
 *   lost `……`; `docs/milestones/v3.md`, spike 2), so agreeing twice is also a
 *   cheap check on the reading itself.
 *
 * Settled lines are published once. A published line that stops being read
 * is withdrawn, after [GRACE_FRAMES] misses so that one bad frame does not
 * make a translation flicker; a published line whose text changes in place is
 * withdrawn at once, because the new text is a new line.
 *
 * Pure and single-threaded: the caller runs it from one coroutine.
 */
class StableLines {

    /** A line once it has settled, with how long settling took. */
    data class Settled(val key: String, val line: GameLine, val settledAfterMs: Long)

    data class Update(val settled: List<Settled>, val gone: List<String>)

    private class Seen(
        val line: GameLine,
        val firstSeenAt: Long,
        var publishedKey: String?,
        var misses: Int = 0,
    )

    private var seen: List<Seen> = emptyList()

    /**
     * @param sceneChanged most of the frame changed since the last one — a
     * cut, a fade, a menu opening. A published line not read on such a frame
     * is withdrawn at once rather than after [GRACE_FRAMES]: its translation
     * is drawn on the panel it erased, and over the next scene that panel is
     * a patch of the last one (measured: a name and a line left on a black
     * fade for over a second).
     */
    fun next(lines: List<GameLine>, now: Long, sceneChanged: Boolean = false): Update {
        val settled = mutableListOf<Settled>()
        val gone = mutableListOf<String>()
        val unmatched = seen.toMutableList()
        val next = mutableListOf<Seen>()

        for (line in lines) {
            val before = unmatched.firstOrNull { samePlace(it.line.bounds, line.bounds) }
            if (before == null) {
                next += Seen(line, now, publishedKey = null)
                continue
            }
            unmatched -= before
            if (before.publishedKey != null && moved(before.line.bounds, line.bounds)) {
                // The same words somewhere else: the affiliation after a
                // longer name. Kept, its translation stayed where the last
                // speaker's was and covered this one's name.
                gone += before.publishedKey!!
                next += Seen(line, now, publishedKey = null)
                continue
            }
            if (before.publishedKey != null && lostItsEnd(before.line.text, line.text)) {
                // One frame read only the first line of a wrapped sentence.
                // That is a miss, not a new line: measured, it withdrew the
                // whole translation and drew it again.
                before.misses += 1
                if (before.misses >= GRACE_FRAMES) gone += before.publishedKey!! else next += before
                continue
            }
            val growing = line.text.length > before.line.text.length &&
                line.text.startsWith(before.line.text)
            if (before.line.text == line.text || (!growing && sameReading(before.line.text, line.text))) {
                // A published line keeps the reading it was published with: on
                // moving art the recogniser reads one still line slightly
                // differently every frame, and each variant used to withdraw
                // the translation and draw it again (`docs/milestones/v3.md`).
                val shown = if (before.publishedKey != null) before.line else line
                val key = before.publishedKey ?: keyOf(line).also {
                    settled += Settled(it, line, now - before.firstSeenAt)
                }
                next += Seen(shown, before.firstSeenAt, key)
            } else {
                // Same place, new words: the old line is over, whatever it was.
                before.publishedKey?.let { gone += it }
                val stillGrowing = line.text.startsWith(before.line.text)
                next += Seen(line, if (stillGrowing) before.firstSeenAt else now, publishedKey = null)
            }
        }

        for (missing in unmatched) {
            val key = missing.publishedKey
            if (key == null) continue
            missing.misses += if (sceneChanged) GRACE_FRAMES else 1
            if (missing.misses >= GRACE_FRAMES) gone += key else next += missing
        }

        seen = next
        return Update(settled, gone)
    }

    /**
     * Two readings of one still line: about the same length and mostly the
     * same characters. A typewriter line grows by more each frame than this
     * allows, and a new line in the same box is different words.
     */
    private fun sameReading(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (abs(a.length - b.length) > max(2, max(a.length, b.length) / 10)) return false
        val shared = a.groupingBy { it }.eachCount().entries.sumOf { (c, n) -> min(n, b.count { it == c }) }
        return shared * 2.0 / (a.length + b.length) >= SAME_READING
    }

    /**
     * Both edges shifted the same way by more than half the line's height.
     * The recogniser's box for a still line wobbles too, but by stretching:
     * measured on dialogue, its left edge 31 px out while the right stayed
     * within 26 — the affiliation after a longer name moved 85 px whole.
     */
    private fun moved(a: TextBounds, b: TextBounds): Boolean {
        val slack = min(a.height, b.height) / 2
        fun shifted(x1: Int, y1: Int, x2: Int, y2: Int) =
            (x2 - x1 > slack && y2 - y1 > slack) || (x1 - x2 > slack && y1 - y2 > slack)
        return shifted(a.left, a.right, b.left, b.right) || shifted(a.top, a.bottom, b.top, b.bottom)
    }

    /**
     * A reading that is clearly shorter than the published one and reads as
     * its beginning — read slightly differently, as a still line on moving art
     * is.
     */
    private fun lostItsEnd(published: String, read: String): Boolean =
        read.length * 2 >= published.length &&
            read.length < published.length - max(2, published.length / 10) &&
            (published.startsWith(read) || sameReading(published.take(read.length), read))

    /** Everything published, for clearing the screen. */
    fun reset(): List<String> {
        val keys = seen.mapNotNull { it.publishedKey }
        seen = emptyList()
        return keys
    }

    private fun keyOf(line: GameLine): String =
        "${line.text}@${line.bounds.left / KEY_GRID},${line.bounds.top / KEY_GRID}"

    /** Overlap of at least half the smaller box: the recogniser's boxes wobble by a few pixels. */
    private fun samePlace(a: TextBounds, b: TextBounds): Boolean {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return false
        val smaller = min(a.width.toLong() * a.height, b.width.toLong() * b.height)
        return w.toLong() * h * 2 >= smaller
    }

    private companion object {
        /**
         * Frames a published line may go unread before it is withdrawn. Two
         * (~0.7 s) let the recogniser's misses on moving art pull a
         * translation down and put it back again and again.
         */
        const val GRACE_FRAMES = 4

        /** Share of characters two readings of one line have in common. */
        const val SAME_READING = 0.85
        const val KEY_GRID = 24
    }
}
