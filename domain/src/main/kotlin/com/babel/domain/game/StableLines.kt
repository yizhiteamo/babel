package com.babel.domain.game

import com.babel.core.model.TextBounds
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

    fun next(lines: List<GameLine>, now: Long): Update {
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
            if (before.line.text == line.text) {
                val key = before.publishedKey ?: keyOf(line).also {
                    settled += Settled(it, line, now - before.firstSeenAt)
                }
                next += Seen(line, before.firstSeenAt, key)
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
            missing.misses += 1
            if (missing.misses >= GRACE_FRAMES) gone += key else next += missing
        }

        seen = next
        return Update(settled, gone)
    }

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
        const val GRACE_FRAMES = 2
        const val KEY_GRID = 24
    }
}
