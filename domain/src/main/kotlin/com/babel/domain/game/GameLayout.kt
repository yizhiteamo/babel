package com.babel.domain.game

import com.babel.core.model.TextBounds
import com.babel.domain.vision.LineJoin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turning the recogniser's lines into what a game actually says: a speaker
 * apart from their affiliation, and one utterance however many lines it wraps
 * over. Measured on Blue Archive (`docs/milestones/v3.md`):
 *
 * - 「ツムギ」 and 「ワイルドハント芸術学院」 come back as one line; the name is
 *   white, the affiliation cyan, 34 px apart.
 * - A line of dialogue that wraps comes back as two separate blocks, and was
 *   translated as two sentences.
 * - The speaker 「通行人A」 sits on the art at the box's edge and scored 15 on
 *   the backdrop test, just over the threshold, so it was never translated.
 */
object GameLayout {

    /** A run of inked columns in a line, its mean ink colour, and how much ink it has. */
    data class InkRun(
        val left: Int,
        val right: Int,
        val red: Int,
        val green: Int,
        val blue: Int,
        val ink: Int = 1,
    )

    /** Ink colours this far apart (0–441) are different pieces of text. */
    const val COLOUR_SPLIT_DISTANCE = 60.0

    /** A gap of at least this fraction of the line's height between them. */
    const val SPLIT_GAP_FRACTION = 0.2

    /**
     * Where a line should be cut into separately set pieces.
     *
     * Each wide enough gap is judged by the colour of **all** the ink on either
     * side of it, weighted by amount, not by its two neighbours: a thin glyph
     * (ト, ッ) is mostly anti-aliased edge, its average pulled toward the
     * background, and judged alone it split one word in two. The gap whose two
     * sides differ most is cut first, then each side is looked at again.
     */
    fun splitPoints(runs: List<InkRun>, lineHeight: Int): List<Int> {
        val cuts = mutableListOf<Int>()
        fun look(from: Int, to: Int) {
            var best = -1
            var bestDistance = COLOUR_SPLIT_DISTANCE
            for (i in from until to) {
                if (runs[i + 1].left - runs[i].right < lineHeight * SPLIT_GAP_FRACTION) continue
                val distance = distance(mean(runs, from, i), mean(runs, i + 1, to))
                if (distance >= bestDistance) {
                    bestDistance = distance
                    best = i
                }
            }
            if (best < 0) return
            cuts += (runs[best].right + runs[best + 1].left) / 2
            look(from, best)
            look(best + 1, to)
        }
        if (runs.size >= 2) look(0, runs.size - 1)
        return cuts.sorted()
    }

    private fun mean(runs: List<InkRun>, from: Int, to: Int): DoubleArray {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0.0
        for (i in from..to) {
            val run = runs[i]
            r += run.red.toDouble() * run.ink
            g += run.green.toDouble() * run.ink
            b += run.blue.toDouble() * run.ink
            n += run.ink
        }
        return doubleArrayOf(r / n, g / n, b / n)
    }

    private fun distance(a: DoubleArray, b: DoubleArray): Double =
        sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    /**
     * The most space a wrapped line leaves below the one above it, against the
     * type's height. Measured on Blue Archive: a wrapped line 34–38 px under a
     * 55–57 px one (0.65); the speaker row 59–72 px above the dialogue (0.86
     * and up) — less than a whole line, and with ruby over the dialogue its
     * box grows to the speaker's height, so the gap is what tells them apart.
     */
    const val WRAP_GAP_FRACTION = 0.75

    /** How far in, in line heights, the first line of an utterance may start. */
    const val FIRST_LINE_INDENT = 2

    /** Line heights within this ratio are the same size of type. */
    const val SAME_SIZE_RATIO = 0.8

    /**
     * Lines that wrap one utterance, joined: the next line starts at about the
     * same left edge, close below, in the same size of type. A speaker's name
     * is set larger and a choice sits a button's height away, so neither joins.
     */
    fun joinWrapped(lines: List<GameLine>): List<GameLine> {
        // A row of two pieces side by side — a name and an affiliation — is a
        // label, not a line of a paragraph. Its height alone does not say so:
        // with ruby over the dialogue, the dialogue's box grew to the name's
        // height and the two were joined (measured on the live game).
        val labels = lines.filter { line -> lines.any { it !== line && sameRow(it.bounds, line.bounds) } }.toSet()
        // Each utterance so far, and the last line it took in: a third line
        // wraps under the second, not under the two of them.
        val out = mutableListOf<Pair<GameLine, GameLine>>()
        for (line in lines.sortedBy { it.bounds.top }) {
            val i = if (line in labels) -1 else out.indexOfLast { (_, last) -> last !in labels && wraps(last, line) }
            if (i < 0) {
                out += line to line
                continue
            }
            val above = out[i].first
            out[i] = GameLine(
                text = LineJoin.join(listOf(above.text, line.text)),
                bounds = TextBounds(
                    min(above.bounds.left, line.bounds.left),
                    above.bounds.top,
                    max(above.bounds.right, line.bounds.right),
                    line.bounds.bottom,
                    above.bounds.space,
                ),
                backdropError = max(above.backdropError, line.backdropError),
                typeHeight = max(above.typeHeight, line.typeHeight),
            ) to line
        }
        return out.map { it.first }
    }

    private fun sameRow(a: TextBounds, b: TextBounds): Boolean =
        min(a.bottom, b.bottom) - max(a.top, b.top) > min(a.height, b.height) / 2

    private fun wraps(above: GameLine, below: GameLine): Boolean {
        val heights = min(above.typeHeight, below.typeHeight).toDouble() / max(above.typeHeight, below.typeHeight)
        val gap = below.bounds.top - above.bounds.bottom
        val h = max(above.typeHeight, below.typeHeight)
        // The next line starts at the margin. The first may start further in:
        // a leading 「……」 is read on some frames and not others, and without
        // it the first line began 83 px right of the second and was not joined.
        val indent = above.bounds.left - below.bounds.left
        return heights >= SAME_SIZE_RATIO &&
            gap in 0..(min(above.bounds.height, below.bounds.height) * WRAP_GAP_FRACTION).toInt() &&
            indent in -h..h * FIRST_LINE_INDENT
    }

    /**
     * The lines worth translating: [GameTextFilter]'s verdicts, plus a speaker
     * label it turned down for its backdrop alone, when a translated line sits
     * right below it and the backdrop is not far over the line — a name above
     * dialogue is part of the dialogue — and a wrap too short to keep alone
     * under a line that is kept.
     */
    fun select(
        lines: List<GameLine>,
        frameHeight: Int,
        frameWidth: Int = Int.MAX_VALUE,
        otherScript: Boolean? = null,
    ): List<GameLine> {
        val verdicts = lines.associateWith { GameTextFilter.judge(it, frameHeight) }
        fun onArtWithin(line: GameLine, allowance: Double) =
            verdicts[line] == GameTextFilter.Verdict.ON_ARTWORK &&
                line.backdropError <= GameTextFilter.MAX_BACKDROP_ERROR * allowance
        // A long line is dialogue: lettering drawn into the art is a word or a
        // title, and a translucent dialogue box over moving art scored 11.6 —
        // a hair under the threshold, and over it on the next frame.
        val controls = controls(lines, frameHeight, frameWidth, otherScript)
        val kept = lines.filter { line -> line !in controls }.filter { line ->
            verdicts[line] == GameTextFilter.Verdict.TRANSLATE ||
                (line.bounds.width >= frameWidth * LONG_LINE_FRACTION && onArtWithin(line, LONG_LINE_ALLOWANCE))
        }.toSet()
        // The end of a wrapped sentence can be a word, 「ね!?」, too short to
        // keep alone; measured, it was dropped and the sentence translated
        // without it.
        fun endsSentence(line: GameLine) =
            verdicts[line] == GameTextFilter.Verdict.TOO_SHORT && kept.any { above -> wraps(above, line) }
        return lines.filter { line ->
            line in kept || endsSentence(line) || (
                onArtWithin(line, SPEAKER_BACKDROP_ALLOWANCE) &&
                    kept.any { below -> speaksAbove(line.bounds, below.bounds) }
                )
        }
    }

    /**
     * A game's own controls written in another script than the game — AUTO,
     * MENU, SKIP and LOG in a Japanese game — left as they are. They are words
     * every player of the game already reads, a chat model asked about one
     * word alone guessed wrong (AUTO came back as "car"), and a translation
     * over them stands between the player and the button.
     *
     * All of: Latin letters only, one word; at the edge of the screen; and on
     * a frame whose text is mostly in another script. In an English game the
     * same words are the game's language and are translated, and a choice or
     * a menu entry, set in the game's own script, never qualifies.
     */
    fun controls(lines: List<GameLine>, frameHeight: Int, frameWidth: Int, otherScript: Boolean? = null): Set<GameLine> {
        val candidates = lines.filter { line ->
            val text = line.text.trim()
            val word = text.isNotEmpty() && text.length <= MAX_CONTROL_LETTERS && text.all(::isLatinLetter)
            val b = line.bounds
            val edge = b.top < frameHeight * EDGE_FRACTION || b.bottom > frameHeight * (1 - EDGE_FRACTION) ||
                (frameWidth != Int.MAX_VALUE && (b.left < frameWidth * EDGE_FRACTION || b.right > frameWidth * (1 - EDGE_FRACTION)))
            word && edge
        }
        if (candidates.isEmpty()) return emptySet()
        val inOtherScript = writtenInOtherScript(lines - candidates.toSet()) ?: otherScript ?: false
        return if (inOtherScript) candidates.toSet() else emptySet()
    }

    /**
     * Whether [lines] are mostly in a script other than Latin, or null when
     * there is too little text to say — a frame showing only AUTO and MENU.
     * The caller remembers the last answer for such frames.
     */
    fun writtenInOtherScript(all: List<GameLine>): Boolean? {
        // A control's own word does not say what the game is written in.
        val lines = all.filterNot { line ->
            val text = line.text.trim()
            text.length <= MAX_CONTROL_LETTERS && text.all(::isLatinLetter)
        }
        val letters = lines.sumOf { line -> line.text.count { it.isLetter() } }
        if (letters < MIN_SCRIPT_LETTERS) return null
        val latin = lines.sumOf { line -> line.text.count(::isLatinLetter) }
        return latin * 2 < letters
    }

    private fun isLatinLetter(c: Char): Boolean = c.isLetter() && c.code < 0x250

    /** Letters needed on a frame to tell which script a game is in. */
    const val MIN_SCRIPT_LETTERS = 6

    /** A control's word is at most this long. */
    const val MAX_CONTROL_LETTERS = 8

    /** The band along each side of the screen where controls sit. */
    const val EDGE_FRACTION = 0.15

    /**
     * How far over the backdrop threshold a speaker label may be. The name row
     * sits on the panel's translucent edge with a character behind it:
     * measured 15 (通行人A) and 31 (ソラ, エンジェル24).
     */
    const val SPEAKER_BACKDROP_ALLOWANCE = 4.0

    /** A line this wide of the frame is dialogue, not lettering on the art. */
    const val LONG_LINE_FRACTION = 0.4
    const val LONG_LINE_ALLOWANCE = 2.0

    private fun speaksAbove(label: TextBounds, line: TextBounds): Boolean {
        val gap = line.top - label.bottom
        // Beside the name too: an affiliation is to its right, on the same row,
        // and the dialogue starts under the name, not under it.
        // And not out to the left of it: lettering on the scenery beside the
        // name row (a sign, 「CENTER」) sat 147 px left of the dialogue, was
        // kept as a speaker and flickered in and out with the art.
        return gap in 0..label.height * 2 && line.left <= label.right &&
            label.left - line.left in -label.height..label.height * 4
    }
}
