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

    /**
     * The most space a wrapped line leaves below the one above it, against the
     * type's height. Measured on Blue Archive: a wrapped line 34–38 px under a
     * 55–57 px one (0.65); the speaker row 59–72 px above the dialogue (0.86
     * and up) — less than a whole line, and with ruby over the dialogue its
     * box grows to the speaker's height. Fate/Grand Order sets its lines
     * further apart, 48 px under a 64 px line (0.75), and at a limit of 0.75
     * the two lines of one utterance were joined on one frame and apart on
     * the next, each publishing over the other. The speaker row is told apart
     * by its type's size and by sitting beside its affiliation, not by the gap.
     */
    const val WRAP_GAP_FRACTION = 0.85

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
                // By width: one line over a busier patch of a patterned panel
                // made a whole profile paragraph fall back to a box.
                backdropError = (above.backdropError * above.bounds.width + line.backdropError * line.bounds.width) /
                    (above.bounds.width + line.bounds.width).coerceAtLeast(1),
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
        val leftAligned = gap in 0..(min(above.bounds.height, below.bounds.height) * WRAP_GAP_FRACTION).toInt() &&
            indent in -h..h * FIRST_LINE_INDENT
        // Or centred, as a game sets an instruction in the middle of the
        // screen — and a little further apart: Fate/Grand Order's tutorial
        // leaves a blank half-line under its heading.
        val centres = (above.bounds.left + above.bounds.right) / 2 - (below.bounds.left + below.bounds.right) / 2
        // Boxes of tightly set lines overlap by a pixel or two.
        val centred = abs(centres) <= h / 2 && gap in -h / 4..(h * CENTRED_GAP_LINES).toInt()
        return heights >= SAME_SIZE_RATIO && (leftAligned || centred)
    }

    /** The space a centred block may leave between its lines, in line heights. */
    const val CENTRED_GAP_LINES = 1.25

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
        }.toMutableSet()
        // A paragraph is kept or dropped whole. Its lines sit on the same
        // panel, but over a patterned one some score higher than others, and
        // measured, a profile's eleven lines came out half translated. A line
        // wrapping on to or under a kept one — a word or two too short to keep
        // alone (「ね!?」), or one over a busier patch of the panel — goes with
        // it, as far as the next line not on the panel.
        fun continues(line: GameLine) =
            (verdicts[line] == GameTextFilter.Verdict.TOO_SHORT || onArtWithin(line, SPEAKER_BACKDROP_ALLOWANCE)) &&
                kept.any { other -> wraps(other, line) || wraps(line, other) }
        // A block of sentences drawn over the art — an instruction over a
        // battle — is the game speaking to the player, not lettering in the
        // scene, which is a word or a line. Two lines or more, one of them a
        // sentence, however busy the art behind: with a limit, one line of
        // the block scored either side of it frame by frame, and the block's
        // translation was withdrawn and drawn again every time.
        val onArt = lines.filter { it !in kept && it !in controls && verdicts[it] == GameTextFilter.Verdict.ON_ARTWORK }
        val blocks = mutableListOf<MutableSet<GameLine>>()
        for (line in onArt.sortedBy { it.bounds.top }) {
            val block = blocks.firstOrNull { b -> b.any { wraps(it, line) || wraps(line, it) } }
            if (block != null) block += line else blocks += mutableSetOf(line)
        }
        for (block in blocks) {
            if (block.size >= 2 && block.any { isSentence(it.text) }) kept += block
        }
        do {
            val more = lines.filter { it !in kept && it !in controls && continues(it) }
            kept += more
        } while (more.isNotEmpty())
        return lines.filter { line ->
            line in kept || (
                onArtWithin(line, SPEAKER_BACKDROP_ALLOWANCE) &&
                    kept.any { below -> speaksAbove(line.bounds, below.bounds) }
                )
        }
    }

    /**
     * Which of [lines] are dialogue — what a player reads, rather than a
     * button they press. Dialogue takes the long press that shows the
     * original, and carries the lines before it as context; anything else is
     * drawn so that every touch goes through to the game.
     *
     * A line is dialogue when it reads as a sentence — a sentence's
     * punctuation or a sentence's length — and so is everything in the same
     * block of text: a piece on the same row (Fate/Grand Order sets
     * 「塩基配列　ヒトゲノム確認」 with wide spaces, and the recogniser returns
     * the halves apart), a line wrapping on to or under it, and the speaker's
     * name above it. Judged by its words alone, 「塩基配列」 was a label; its
     * window let a long press through and the game moved on a line.
     */
    fun dialogue(lines: List<GameLine>): Set<GameLine> {
        val found = lines.filter { isSentence(it.text) }.toMutableSet()
        do {
            val more = lines.filter { line ->
                line !in found && found.any { d ->
                    sameRow(line.bounds, d.bounds) || wraps(line, d) || wraps(d, line) || speaksAbove(line.bounds, d.bounds)
                }
            }
            found += more
        } while (more.isNotEmpty())
        return found
    }

    /**
     * Pieces of one line of dialogue set apart by wide spaces, joined back
     * into the line. Fate/Grand Order writes 「貴方は本日　最後の来館者です。」
     * with a gap the recogniser cuts at, and translated apart the halves came
     * back as "you today" and "you are the last visitor".
     *
     * Only dialogue ([dialogue]), only type of one size — a speaker's name is
     * set larger than the affiliation beside it, and the two stay apart — and
     * only gaps of a few characters. The gap is kept as a wide space, so the
     * translation can keep it too.
     */
    fun joinRows(lines: List<GameLine>, dialogue: Set<GameLine>): List<GameLine> {
        val out = mutableListOf<GameLine>()
        for (line in lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            val i = if (line !in dialogue) {
                -1
            } else {
                out.indexOfLast { before ->
                    before in dialogue && sameRow(before.bounds, line.bounds) &&
                        line.bounds.left >= before.bounds.right &&
                        line.bounds.left - before.bounds.right <= max(before.typeHeight, line.typeHeight) * ROW_GAP_LINES &&
                        min(before.typeHeight, line.typeHeight).toDouble() / max(before.typeHeight, line.typeHeight) >= SAME_ROW_SIZE_RATIO
                }
            }
            if (i < 0) {
                out += line
                continue
            }
            val before = out[i]
            out[i] = GameLine(
                text = before.text.trimEnd() + WIDE_SPACE + line.text.trimStart(),
                bounds = TextBounds(
                    before.bounds.left,
                    min(before.bounds.top, line.bounds.top),
                    line.bounds.right,
                    max(before.bounds.bottom, line.bounds.bottom),
                    before.bounds.space,
                ),
                backdropError = (before.backdropError * before.bounds.width + line.backdropError * line.bounds.width) /
                    (before.bounds.width + line.bounds.width).coerceAtLeast(1),
                typeHeight = max(before.typeHeight, line.typeHeight),
            )
        }
        return out
    }

    /** The widest gap, in characters, inside one line of dialogue. */
    const val ROW_GAP_LINES = 4

    /** Name 70 px against affiliation 55 is 0.79; pieces of one line are one size. */
    const val SAME_ROW_SIZE_RATIO = 0.85

    private const val WIDE_SPACE = '　'

    /** A sentence's punctuation, or more letters than a label has. */
    fun isSentence(text: String): Boolean =
        text.any { it in SENTENCE_MARKS } || text.count { it.isLetter() } > MAX_LABEL_LETTERS

    const val SENTENCE_MARKS = "。、？！?!…「」『』"

    /** Longer than this without punctuation is still a sentence, not a label. */
    const val MAX_LABEL_LETTERS = 12

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
            val word = isControlWord(line.text)
            val b = line.bounds
            val edge = b.top < frameHeight * EDGE_FRACTION || b.bottom > frameHeight * (1 - EDGE_FRACTION) ||
                (frameWidth != Int.MAX_VALUE && (b.left < frameWidth * EDGE_FRACTION || b.right > frameWidth * (1 - EDGE_FRACTION)))
            word && edge
        }
        if (candidates.isEmpty()) return emptySet()
        // Not yet known — a fade-in showing only 「SKIP」 — counts as another
        // script: translated then, the word stayed translated (「跳过」) for the
        // whole scene. An English game has its controls translated a moment
        // later, once there is text enough to tell.
        val inOtherScript = writtenInOtherScript(lines - candidates.toSet()) ?: otherScript ?: true
        return if (inOtherScript) candidates.toSet() else emptySet()
    }

    /**
     * Whether [lines] are mostly in a script other than Latin, or null when
     * there is too little text to say — a frame showing only AUTO and MENU.
     * The caller remembers the last answer for such frames.
     */
    fun writtenInOtherScript(all: List<GameLine>): Boolean? {
        // A control's own word does not say what the game is written in.
        val lines = all.filterNot { isControlWord(it.text) }
        val letters = lines.sumOf { line -> line.text.count { it.isLetter() } }
        if (letters < MIN_SCRIPT_LETTERS) return null
        val latin = lines.sumOf { line -> line.text.count(::isLatinLetter) }
        return latin * 2 < letters
    }

    private fun isLatinLetter(c: Char): Boolean = c.isLetter() && c.code < 0x250

    /**
     * One Latin word, with at most the arrows or marks a button draws beside
     * it: Fate/Grand Order's 「SKIP▶▶」 read as 「SKIP»」 on some frames, and
     * those frames translated it.
     */
    private fun isControlWord(text: String): Boolean {
        val letters = text.filter { it.isLetterOrDigit() }
        return letters.length in 2..MAX_CONTROL_LETTERS && letters.all(::isLatinLetter) && ' ' !in text.trim()
    }

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

    /** Whether [label] sits where a speaker's name does, just above and over the start of [line]. */
    fun speaksAbove(label: TextBounds, line: TextBounds): Boolean {
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
