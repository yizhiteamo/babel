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

    /** Why a published line was withdrawn, for the log: the numbers say which rule fires. */
    enum class Why { MOVED, CUT_SHORT, NEW_WORDS, MISSED, SCENE, BLANK }

    data class Update(val settled: List<Settled>, val gone: List<String>, val why: Map<String, Why> = emptyMap())

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
    fun next(
        lines: List<GameLine>,
        now: Long,
        sceneChanged: Boolean = false,
        /**
         * Whether the frame still shows ink where a line was. A line the
         * recogniser missed is still there and keeps its grace; a line whose
         * place is blank panel is gone. Measured: between two lines of
         * Fate/Grand Order the box stood empty for four frames, the last
         * translation stayed over it, and the next line typed out under it.
         */
        stillInked: (GameLine) -> Boolean = { true },
    ): Update {
        val settled = mutableListOf<Settled>()
        val gone = mutableListOf<String>()
        val why = mutableMapOf<String, Why>()
        val unmatched = seen.toMutableList()
        val next = mutableListOf<Seen>()

        for (line in lines) {
            val before = unmatched.firstOrNull { samePlace(it.line.bounds, line.bounds) }
            if (before == null) {
                next += fresh(line, now, settled)
                continue
            }
            unmatched -= before
            if (before.publishedKey != null && moved(before.line.bounds, line.bounds)) {
                // The same words somewhere else: the affiliation after a
                // longer name. Kept, its translation stayed where the last
                // speaker's was and covered this one's name.
                gone += before.publishedKey!!
                why[before.publishedKey!!] = Why.MOVED
                next += fresh(line, now, settled)
                continue
            }
            if (before.publishedKey != null && lostItsEnd(before.line.text, line.text)) {
                // One frame read only the first line of a wrapped sentence.
                // That is a miss, not a new line: measured, it withdrew the
                // whole translation and drew it again.
                before.misses += 1
                if (before.misses >= GRACE_FRAMES) {
                    gone += before.publishedKey!!
                    why[before.publishedKey!!] = Why.CUT_SHORT
                } else {
                    next += before
                }
                continue
            }
            if (before.publishedKey != null && sliding(before.line.bounds, line.bounds) &&
                (before.line.text == line.text || sameReading(before.line.text, line.text))
            ) {
                // A published line still moving, less than [moved] withdraws:
                // a chat scrolled to a stop, settled while it slowed, and its
                // patch stood 8 px above the bubble with the bottom of the
                // words showing under it. Settled again where it is now, under
                // its own key: the translation moves, it is not done again.
                val key = before.publishedKey!!
                val there = before.line.copy(bounds = line.bounds, typeHeight = line.typeHeight)
                settled += Settled(key, there, 0)
                next += Seen(there, before.firstSeenAt, key)
                continue
            }
            val growing = line.text.length > before.line.text.length &&
                line.text.startsWith(before.line.text)
            if (before.publishedKey == null && sliding(before.line.bounds, line.bounds) &&
                (before.line.text == line.text || sameReading(before.line.text, line.text))
            ) {
                // Same words, still moving: a name sliding in. Settled there,
                // its translation was drawn where the name was half-way, and
                // the patch erased only the lower half of it.
                next += Seen(line, before.firstSeenAt, publishedKey = null)
                continue
            }
            // 「接続中」 with its dots counting up is one line, not a new one
            // three times a second.
            val sameWords = core(before.line.text).let { it.isNotEmpty() && it == core(line.text) }
            if (before.line.text == line.text || sameWords || (!growing && sameReading(before.line.text, line.text))) {
                // A published line keeps the reading it was published with: on
                // moving art the recogniser reads one still line slightly
                // differently every frame, and each variant used to withdraw
                // the translation and draw it again (`docs/milestones/v3.md`).
                val shown = if (before.publishedKey != null) before.line else line
                val key = before.publishedKey ?: keyOf(line).also {
                    settled += Settled(it, line, now - before.firstSeenAt)
                    remember(line)
                    noteReturn(line, now)
                }
                next += Seen(shown, before.firstSeenAt, key)
            } else {
                // Same place, new words: the old line is over, whatever it was.
                val published = before.publishedKey
                if (published != null && (flips(before.line, line) || akin(before.line.text, line.text))) {
                    // Two readings that have taken turns here before: one
                    // place read two ways, not a new line. Kept as a miss.
                    before.misses += 1
                    if (before.misses >= GRACE_FRAMES) {
                        gone += published
                        why[published] = Why.MISSED
                    } else {
                        next += before
                    }
                    continue
                }
                if (published != null) {
                    gone += published
                    why[published] = Why.NEW_WORDS
                    replaced += Replaced(before.line, line.text, now)
                }
                val stillGrowing = line.text.startsWith(before.line.text)
                next += if (stillGrowing) Seen(line, before.firstSeenAt, publishedKey = null) else fresh(line, now, settled)
            }
        }

        for (missing in unmatched) {
            val key = missing.publishedKey
            if (key == null) continue
            val blank = !sceneChanged && !stillInked(missing.line)
            missing.misses += if (sceneChanged || blank) GRACE_FRAMES else 1
            if (missing.misses >= GRACE_FRAMES) {
                gone += key
                why[key] = when {
                    sceneChanged -> Why.SCENE
                    blank -> Why.BLANK
                    else -> Why.MISSED
                }
            } else {
                next += missing
            }
        }

        seen = next
        return Update(settled, gone, why)
    }

    /**
     * Two readings of one still line: about the same length and mostly the
     * same characters. A typewriter line grows by more each frame than this
     * allows, and a new line in the same box is different words.
     */
    private fun sameReading(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        // A short label misread by one character — 「風紀委員会」 against a
        // variant one glyph off — shares too few characters for the rule
        // below and was withdrawn and drawn again on every flip: the speaker
        // row flickered. One edit in four or more characters is the same
        // label; a two-letter name keeps the strict rule, where one character
        // is half the name.
        val longer = max(a.length, b.length)
        // Two in eight or more: an enemy's name over its health bar on a
        // battlefield came back four ways — a stray 「、」 or 「1」 in front, its
        // last glyph misread — and every variant withdrew the last.
        if (longer >= MIN_EDITABLE_LENGTH && editDistance(a, b) <= ((longer + 1) / 4).coerceIn(1, 3)) return true
        if (abs(a.length - b.length) > max(2, max(a.length, b.length) / 10)) return false
        val shared = a.groupingBy { it }.eachCount().entries.sumOf { (c, n) -> min(n, b.count { it == c }) }
        return shared * 2.0 / (a.length + b.length) >= SAME_READING
    }

    /** Top and bottom both moved the same way by more than a recogniser's wobble. */
    private fun sliding(a: TextBounds, b: TextBounds): Boolean {
        val slack = max(MIN_SLIDE_PX, min(a.height, b.height) / SLIDE_DIVISOR)
        return (b.top - a.top > slack && b.bottom - a.bottom > slack) ||
            (a.top - b.top > slack && a.bottom - b.bottom > slack)
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
        read.length * 3 >= published.length && read.length >= MIN_CUT_LENGTH &&
            read.length < published.length - max(2, published.length / 10) &&
            (
                published.startsWith(read) || sameReading(published.take(read.length), read) ||
                    // Or any part of it, every character in order: over the
                    // animated art behind a history screen, a two-line entry
                    // was read whole on one frame and without its first line
                    // on the next (20 characters, then 13), and each reading
                    // withdrew the other.
                    isSubsequence(read, published) ||
                    // Its start, a mark or two misread: 「……あの」 for
                    // 「……あの……せん、ぱい」 with an ellipsis read as dots.
                    editDistance(published.take(read.length), read) <= max(1, read.length / 5) ||
                    // Or most of it in order, a character or two misread or
                    // stray — 「あ·」, 「Lアンサモン…」 for 「「アンサモン…」.
                    commonSubsequence(read, published) >= read.length - max(1, read.length * 15 / 100)
                )

    private fun commonSubsequence(a: String, b: String): Int {
        var previous = IntArray(b.length + 1)
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            for (j in 1..b.length) {
                current[j] = if (a[i - 1] == b[j - 1]) previous[j - 1] + 1 else maxOf(previous[j], current[j - 1])
            }
            previous = current
        }
        return previous[b.length]
    }

    private fun isSubsequence(part: String, whole: String): Boolean {
        var i = 0
        for (c in whole) if (i < part.length && part[i] == c) i++
        return i == part.length
    }

    /** A line without the dots an animation adds and takes away at its end. */
    private fun core(text: String): String = text.trimEnd(*TRAILING_MARKS)

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitute, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }

    /**
     * Lines published before, by their words: a speaker's name comes back
     * line after line, and waiting for two frames to agree on it showed the
     * original name for a moment at every change of speaker. A line read
     * exactly as one already published is published at once. Labels always;
     * dialogue only from [MIN_KNOWN_SENTENCE] characters — a short one may be
     * the start of a longer line still typing.
     */
    private val knownLabels = LinkedHashSet<String>()

    /**
     * A name read one character off a name already published, as that name:
     * 「チナッ」 for 「チナツ」 was translated as another person's name. Labels
     * of three characters or more, of the same length, one character apart.
     */
    private fun snapped(line: GameLine): GameLine {
        if (!GameLayout.isLabel(line.text) || line.text.length < MIN_SNAP_LENGTH || line.text in knownLabels) return line
        val known = knownLabels.lastOrNull { it.length == line.text.length && editDistance(it, line.text) == 1 } ?: return line
        return line.copy(text = known)
    }

    private fun remember(line: GameLine) {
        // A line of dialogue too, once it is long enough not to be the start
        // of another: scrolled back into view on a history screen, each line
        // waited two frames to agree on words it had already been published
        // with. A shorter one may be a line still typing.
        if (!GameLayout.isLabel(line.text) && line.text.length < MIN_KNOWN_SENTENCE) return
        knownLabels.remove(line.text)
        knownLabels += line.text
        while (knownLabels.size > MAX_KNOWN_LABELS) knownLabels.remove(knownLabels.first())
    }

    private fun fresh(read: GameLine, now: Long, settled: MutableList<Settled>): Seen {
        val line = snapped(read)
        if (line.text !in knownLabels) return Seen(line, now, publishedKey = null)
        noteReturn(line, now)
        val key = keyOf(line)
        settled += Settled(key, line, 0)
        return Seen(line, now, key)
    }

    /**
     * Published lines withdrawn for other words, and those words, for a few
     * seconds: when the old words come back to the same place, the place is
     * being read two ways — measured on a history screen over the game's
     * dimmed dialogue box, which the recogniser read now one way and now the
     * other, twice a second — and from then on the second reading does not
     * withdraw the first.
     */
    private data class Replaced(val line: GameLine, val by: String, val at: Long)

    private val replaced = mutableListOf<Replaced>()
    private val flipping = mutableListOf<Pair<GameLine, String>>()

    private fun noteReturn(line: GameLine, now: Long) {
        replaced.removeAll { now - it.at > FLIP_WINDOW_MS }
        val back = replaced.firstOrNull { it.line.text == line.text && samePlace(it.line.bounds, line.bounds) } ?: return
        replaced -= back
        flipping.removeAll { it.first.text == line.text }
        flipping += line to back.by
        while (flipping.size > MAX_FLIPPING) flipping.removeAt(0)
    }

    /**
     * Mostly the same characters, though more than a misread apart: the same
     * place read with something else mixed in. Over a history screen laid on
     * the game's dialogue box, one entry came back in six readings, the
     * dimmed line behind taken in or not, and each withdrew the last. A new
     * line in the same place shares far fewer: measured under 0.4, these 0.6
     * and over.
     */
    private fun akin(a: String, b: String): Boolean {
        if (a.length < MIN_AKIN_LENGTH || b.length < MIN_AKIN_LENGTH) return false
        val shared = a.groupingBy { it }.eachCount().entries.sumOf { (c, n) -> min(n, b.count { it == c }) }
        return shared * 2.0 / (a.length + b.length) >= AKIN_SHARE
    }

    private fun flips(published: GameLine, read: GameLine): Boolean =
        flipping.any { (line, other) ->
            line.text == published.text && samePlace(line.bounds, read.bounds) &&
                (other == read.text || sameReading(other, read.text))
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
        /**
         * Frames a published line may go unread before it is withdrawn. Two
         * (~0.7 s) let the recogniser's misses on moving art pull a
         * translation down and put it back again and again.
         */
        const val GRACE_FRAMES = 4

        /**
         * A still line's box wobbles a few pixels up or down between frames
         * (measured up to 11 on a name 85 tall); more than this, both edges
         * together, is the line moving.
         */
        const val MIN_SLIDE_PX = 6
        const val SLIDE_DIVISOR = 6

        /** See [akin]. */
        const val AKIN_SHARE = 0.6
        const val MIN_AKIN_LENGTH = 8

        /** How long a withdrawn line is watched for coming back; see [Replaced]. */
        const val FLIP_WINDOW_MS = 4000L
        const val MAX_FLIPPING = 16

        /** Shortest label a one-character misread is corrected in; see [snapped]. */
        const val MIN_SNAP_LENGTH = 3

        /** Labels remembered for publishing at once; see [knownLabels]. */
        const val MAX_KNOWN_LABELS = 256

        /** Dialogue this long is published at once when read again exactly. */
        const val MIN_KNOWN_SENTENCE = 12

        /** Shortest reading one misread character may differ in and stay the same line. */
        const val MIN_EDITABLE_LENGTH = 4

        /**
         * What an animation or a misread adds and takes away at a line's end:
         * dots counting up, and — on a history screen over moving art — a
         * closing 「」」 or 「！」 read on one frame and not the next.
         */
        val TRAILING_MARKS = charArrayOf('.', '。', '…', '・', '‥', '･', ' ', '!', '！', '?', '？', '」', '』', '"', '”', '|', '｜')

        /** A reading shorter than this is too little to be sure it is the same line cut short. */
        const val MIN_CUT_LENGTH = 3

        /** Share of characters two readings of one line have in common. */
        const val SAME_READING = 0.85
        const val KEY_GRID = 24
    }
}
