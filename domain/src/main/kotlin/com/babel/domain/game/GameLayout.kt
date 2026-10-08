package com.babel.domain.game

import com.babel.core.model.TextBounds
import com.babel.domain.vision.LineJoin
import kotlin.math.abs
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

    /** Line heights within this ratio are the same size of type. */
    const val SAME_SIZE_RATIO = 0.8

    /**
     * Lines that wrap one utterance, joined: the next line starts at about the
     * same left edge, close below, in the same size of type. A speaker's name
     * is set larger and a choice sits a button's height away, so neither joins.
     */
    fun joinWrapped(lines: List<GameLine>): List<GameLine> {
        val out = mutableListOf<GameLine>()
        for (line in lines.sortedBy { it.bounds.top }) {
            val i = out.indexOfLast { wraps(it.bounds, line.bounds) }
            if (i < 0) {
                out += line
                continue
            }
            val above = out[i]
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
            )
        }
        return out
    }

    private fun wraps(above: TextBounds, below: TextBounds): Boolean {
        val heights = min(above.height, below.height).toDouble() / max(above.height, below.height)
        val gap = below.top - above.bottom
        val h = max(above.height, below.height)
        return heights >= SAME_SIZE_RATIO && gap in 0..h && abs(below.left - above.left) <= h
    }

    /**
     * The lines worth translating: [GameTextFilter]'s verdicts, plus a speaker
     * label it turned down for its backdrop alone, when a translated line sits
     * right below it and the backdrop is not far over the line — a name above
     * dialogue is part of the dialogue.
     */
    fun select(lines: List<GameLine>, frameHeight: Int, frameWidth: Int = Int.MAX_VALUE): List<GameLine> {
        val verdicts = lines.associateWith { GameTextFilter.judge(it, frameHeight) }
        fun onArtWithin(line: GameLine, allowance: Double) =
            verdicts[line] == GameTextFilter.Verdict.ON_ARTWORK &&
                line.backdropError <= GameTextFilter.MAX_BACKDROP_ERROR * allowance
        // A long line is dialogue: lettering drawn into the art is a word or a
        // title, and a translucent dialogue box over moving art scored 11.6 —
        // a hair under the threshold, and over it on the next frame.
        val kept = lines.filter { line ->
            verdicts[line] == GameTextFilter.Verdict.TRANSLATE ||
                (line.bounds.width >= frameWidth * LONG_LINE_FRACTION && onArtWithin(line, LONG_LINE_ALLOWANCE))
        }.toSet()
        return lines.filter { line ->
            line in kept || (
                onArtWithin(line, SPEAKER_BACKDROP_ALLOWANCE) &&
                    kept.any { below -> speaksAbove(line.bounds, below.bounds) }
                )
        }
    }

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
        return gap in 0..label.height * 2 && line.left <= label.right && label.left - line.left <= label.height * 4
    }
}
