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
     * further apart, 48 px under a 64 px line (0.75), its boxes sometimes
     * tighter than the type; against the smaller box at 0.75, then 0.85, the
     * two lines of one utterance were joined on one frame and apart on the
     * next, half of it translated at a time. Against the larger box, 0.9. The
     * speaker row is told apart by its type's size ([SAME_SIZE_RATIO]: a name
     * 68 px over dialogue 55 is 0.81) and by sitting beside its affiliation,
     * not by the gap.
     */
    const val WRAP_GAP_FRACTION = 0.9

    /**
     * How far in, in line heights, the first line of an utterance may start.
     * Fate/Grand Order's 「――ふう。」 read without its dash began 194 px (3.0
     * heights) in, was joined on the frames that read the dash and apart on
     * the others, and showed as one box or two.
     */
    const val FIRST_LINE_INDENT = 3.5

    /** Line heights within this ratio are the same size of type. */
    const val SAME_SIZE_RATIO = 0.85

    /**
     * Lines that wrap one utterance, joined: the next line starts at about the
     * same left edge, close below, in the same size of type. A speaker's name
     * is set larger and a choice sits a button's height away, so neither joins.
     */
    /**
     * Whether two lines stand on one panel, for a line to wrap on to the
     * next; the caller can see the pixels. The home screen's 「ショップ」, just
     * under a chat window and set where the last bubble's next line would
     * be, was read as that line.
     */
    fun interface Panels {
        fun joined(above: GameLine, below: GameLine): Boolean
    }

    private val ANY_PANEL = Panels { _, _ -> true }

    fun joinWrapped(lines: List<GameLine>, panels: Panels = ANY_PANEL): List<GameLine> {
        // A row of two pieces side by side — a name and an affiliation — is a
        // label, not a line of a paragraph. Its height alone does not say so:
        // with ruby over the dialogue, the dialogue's box grew to the name's
        // height and the two were joined (measured on the live game).
        val labels = lines.filter { line ->
            // Beside it, not across the screen: two cards side by side put every
            // line of one synopsis on a row with a line of the other, and no
            // paragraph was joined.
            lines.any { it !== line && sameRow(it.bounds, line.bounds) && nearAlongRow(it, line) } || namesNext(line, lines)
        }.toSet()
        // A label said more than once on the screen is a name, a sender's
        // over each run of her messages: 「アリス(臨戦)」 over 「先生!」 was
        // joined into it as its first line.
        val repeated = lines.filter { isLabel(it.text) }.groupingBy { it.text }.eachCount().filterValues { it >= 2 }.keys
        // Each utterance so far, and the last line it took in: a third line
        // wraps under the second, not under the two of them.
        val out = mutableListOf<Pair<GameLine, GameLine>>()
        for (line in lines.sortedBy { it.bounds.top }) {
            // A label in smaller type under a line is the next speaker's name,
            // not the line's last word — even with nothing kept under it: the
            // 「2」 under 「アナウンス」 was a number, left out, and the name was
            // read as the end of the line above (「……还有3，播报」).
            val nameLike = isLabel(line.text)
            val i = if (line in labels) -1 else out.indexOfLast { (_, last) ->
                last !in labels && last.text !in repeated && wraps(last, line) && panels.joined(last, line) &&
                    !(nameLike && line.typeHeight.toDouble() / last.typeHeight.coerceAtLeast(1) < NAME_TYPE_RATIO) &&
                    // Nor a label in smaller type over a line: a chat sender's
                    // name over a bubble with no punctuation — 「ケイ」 over
                    // 「寮に着きました」 — came back as "arrived at Kei's dorm".
                    !(isLabel(last.text) && last.typeHeight.toDouble() / line.typeHeight.coerceAtLeast(1) < NAME_TYPE_RATIO) &&
                    // Nor one in clearly larger type: a chat list's name over
                    // its last message — 「アリス (臨戦)」, 45 px over 「先生!」's
                    // 40 — was set as one wrapped line.
                    !(isLabel(last.text) && last.typeHeight >= line.typeHeight * HEADING_TYPE_RATIO) &&
                    // Nor a label that stops well short of the line under it:
                    // a line wraps where it runs out of room. A chat list's
                    // 「カスミ(水着)」 over its last message was joined into it.
                    // MomoTalk's 「ケイ」 ended 26 px short of the 「はい」 under it.
                    !(isLabel(last.text) && last.bounds.right < line.bounds.right - line.typeHeight / 2) &&
                    // Nor two labels: a line wraps when it is long, and two
                    // short ones stacked are two things — a chat's 「んー」 and
                    // 「先生」, a bubble each, came back as one translation
                    // drawn across both.
                    !(isLabel(last.text) && isLabel(line.text)) &&
                    // Nor a label over a line starting to the right of it: a
                    // sender's 「リオ(臨戦)」 sits out to the left of her
                    // bubble's 「……本当?」, and a wrapped line runs back to the
                    // margin, not past it.
                    !(isLabel(last.text) && line.bounds.left > last.bounds.left + line.typeHeight / 4)
            }
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

    /**
     * A speaker's name between two utterances, as a history screen lists
     * them: a label in smaller type with a sentence starting under it. Joined
     * as the last line of the utterance above, it came back as
     * 「……那个，前辈。玛修」 — and only on the frames where the art behind the
     * history happened to let the two be read alike, so the line flickered.
     */
    private fun namesNext(line: GameLine, lines: List<GameLine>): Boolean {
        if (!isLabel(line.text)) return false
        return lines.any { below ->
            below !== line && isSentence(below.text) && below.bounds.top >= line.bounds.bottom - line.bounds.height / 4 &&
                speaksAbove(line.bounds, below.bounds) &&
                // At the line's margin or out to its left; a centred heading
                // over centred lines starts further in.
                line.bounds.left - below.bounds.left <= line.bounds.height / 2 &&
                line.typeHeight.toDouble() / below.typeHeight.coerceAtLeast(1) < NAME_TYPE_RATIO
        }
    }

    /** A name is set this much smaller than its line, or less: measured 0.84–0.93 against 1.0 for a wrap. */
    const val NAME_TYPE_RATIO = 0.95

    /** A label this much larger than the line under it heads it, and is not its first line. */
    const val HEADING_TYPE_RATIO = 1.1

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
        val leftAligned = gap in 0..(max(above.bounds.height, below.bounds.height) * WRAP_GAP_FRACTION).toInt() &&
            indent in -h..(h * FIRST_LINE_INDENT).toInt()
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
        before: List<GameLine> = emptyList(),
        /** Where speakers' names have stood before; see [speakerSpots]. */
        speakerSpots: List<TextBounds> = emptyList(),
        /** A chat's columns seen lately; see [ChatColumn]. */
        columns: List<ChatColumn> = emptyList(),
    ): List<GameLine> {
        // Sizes against the screen's short side: held upright, a game's frame
        // is 3200 tall, and its 50 px body text measured as scenery.
        val scale = min(frameHeight, frameWidth)
        val verdicts = lines.associateWith { GameTextFilter.judge(it, scale) }
        fun onArtWithin(line: GameLine, allowance: Double) =
            verdicts[line] == GameTextFilter.Verdict.ON_ARTWORK &&
                line.backdropError <= GameTextFilter.MAX_BACKDROP_ERROR * allowance
        // A long line is dialogue: lettering drawn into the art is a word or a
        // title, and a translucent dialogue box over moving art scored 11.6 —
        // a hair under the threshold, and over it on the next frame.
        val controls = controls(lines, frameHeight, frameWidth, otherScript)
        val voiced = underRepeatedNames(lines)
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
            (
                verdicts[line] == GameTextFilter.Verdict.TOO_SHORT || verdicts[line] == GameTextFilter.Verdict.TOO_SMALL ||
                    onArtWithin(line, SPEAKER_BACKDROP_ALLOWANCE)
                ) &&
                kept.any { other -> wraps(other, line) || wraps(line, other) }
        // A block of sentences drawn over the art — an instruction over a
        // battle — is the game speaking to the player, not lettering in the
        // scene, which is a word or a line. Two lines or more, one of them a
        // sentence, however busy the art behind: with a limit, one line of
        // the block scored either side of it frame by frame, and the block's
        // translation was withdrawn and drawn again every time.
        // Small type too, in a paragraph: a story card's synopsis is set at
        // 30–32 px on a 1440 px screen, on either side of the size scenery is
        // cut at, and came out translated one line in two. A price tag is
        // one line; a paragraph of sentences is not scenery.
        val onArt = lines.filter {
            it !in kept && it !in controls &&
                (verdicts[it] == GameTextFilter.Verdict.ON_ARTWORK || verdicts[it] == GameTextFilter.Verdict.TOO_SMALL)
        }
        val blocks = mutableListOf<MutableSet<GameLine>>()
        for (line in onArt.sortedBy { it.bounds.top }) {
            val block = blocks.firstOrNull { b -> b.any { wraps(it, line) || wraps(line, it) } }
            if (block != null) block += line else blocks += mutableSetOf(line)
        }
        for (block in blocks) {
            // A sentence by its punctuation, not its length: a title logo of
            // three words over the art — 「SHATTERED / PIXEL / DUNGEON」 — is long
            // enough to pass for one, and was translated.
            if (block.size >= 2 && block.any { line -> line.text.any { it in SENTENCE_MARKS } }) kept += block
        }
        do {
            val more = lines.filter { it !in kept && it !in controls && continues(it) }
            kept += more
        } while (more.isNotEmpty())
        // A one-letter exclamation in a chat — MomoTalk's 「あ!」 — is too short
        // to keep alone, but it stands in the column of the bubbles around it.
        // Only with a sentence's mark: a lone letter beside a list is not one.
        // In a chat's column seen lately, any letter: a stammer split over
        // bubbles — 「た」, 「すけて」 — is one letter to a bubble.
        kept += lines.filter { line ->
            line !in kept && line !in controls && verdicts[line] == GameTextFilter.Verdict.TOO_SHORT &&
                line.text.any { it.isLetter() } &&
                ((punctuated(line.text) && kept.any { other -> inColumnWith(line, other) }) || columns.any { it.holds(line) } || line in voiced)
        }
        // A name set out to the left of the dialogue — Fate/Grand Order's tab,
        // 107 px out — only when it sits on the panel: the scenery's 「CENTER」
        // stood as far out (147 px) on busy art and was taken for a speaker.
        fun speaks(label: GameLine, below: GameLine): Boolean {
            if (!speaksAbove(label.bounds, below.bounds)) return false
            val outLeft = below.bounds.left - label.bounds.left > label.bounds.height
            // Out to the left on busy art, a name is still a name when it is
            // written in a script other than Latin: the scenery's 「CENTER」
            // was Latin lettering, and 「フォウ」, 「Dr.ロマン」 over a character
            // behind Fate/Grand Order's tab scored up to 32.
            val named = label.text.any { it.isLetter() && it.code >= 0x250 }
            return !outLeft || label.backdropError <= GameTextFilter.MAX_BACKDROP_ERROR * LONG_LINE_ALLOWANCE ||
                named || speakerSpots.any { at(it, label.bounds) }
        }
        // A line of silence — 「……………」 — has nothing to translate, but its
        // speaker is still named above it: 「フォウ」 stood untranslated over
        // every one.
        // So is a line that is only a number — a countdown's 「2」 under
        // 「アナウンス」.
        val silences = lines.filter { line ->
            (line.text.length >= 2 && line.text.all { it in SILENCE_MARKS } && line.bounds.width >= line.bounds.height * 2) ||
                (verdicts[line] == GameTextFilter.Verdict.NUMBER && line.text.any { it.isDigit() })
        }
        // A speaker kept on the last frame stays while it reads the same in
        // the same place: as the next line starts typing, the dialogue under
        // it is a character or two too short to keep, and the name dropped
        // out, was withdrawn and translated again on every line.
        fun stays(line: GameLine) = before.any { b ->
            b.text == line.text && abs(b.bounds.left - line.bounds.left) <= line.bounds.height / 2 &&
                abs(b.bounds.top - line.bounds.top) <= line.bounds.height / 2
        }
        // Never an interface word or anything with digits, kept or staying:
        // read while the chat window was opening, 「90 MAX 食MomoTalk ?」 and
        // the player's name stood over each other like a speaker over a line,
        // and then stayed, frame after frame, for reading the same.
        return lines.filter { line ->
            line in kept || (
                line !in controls && line.text.none { it.isDigit() } &&
                    onArtWithin(line, SPEAKER_BACKDROP_ALLOWANCE) &&
                    ((kept + silences).any { below -> speaks(line, below) } || stays(line))
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
    fun dialogue(
        lines: List<GameLine>,
        places: List<TextBounds> = emptyList(),
        columns: List<ChatColumn> = emptyList(),
        senders: Set<String> = emptySet(),
        panels: Panels = ANY_PANEL,
    ): Set<GameLine> = spokenAndNames(lines, places, columns, senders, panels).first

    /**
     * A column of a chat's messages: their common left edge (or right, for
     * the player's side), their type, and how far down the screen it runs.
     * Remembered by the caller, it is where dialogue is after the chat
     * scrolls to messages none of which reads as a sentence — 「た」, 「すけて」,
     * 「いま」, 「ああ」, a screen of short replies — and nothing was translated.
     */
    data class ChatColumn(val edge: Int, val rightAligned: Boolean, val typeHeight: Int, val top: Int, val bottom: Int) {
        fun holds(line: GameLine): Boolean {
            val h = max(line.typeHeight, typeHeight)
            val x = if (rightAligned) line.bounds.right else line.bounds.left
            val reach = if (rightAligned) h else h * 3 / 4
            return abs(x - edge) <= reach &&
                min(line.typeHeight, typeHeight).toDouble() / h >= COLUMN_SIZE_RATIO &&
                line.bounds.bottom > top - h * COLUMN_SPAN_SLACK_LINES && line.bounds.top < bottom + h * COLUMN_SPAN_SLACK_LINES
        }

        fun merged(other: ChatColumn) = copy(top = min(top, other.top), bottom = max(bottom, other.bottom))

        fun sameAs(other: ChatColumn) =
            rightAligned == other.rightAligned && abs(edge - other.edge) <= max(typeHeight, other.typeHeight) / 2
    }

    /** The columns [spoken] lines stand in, three lines or more to a column. */
    fun chatColumns(spoken: Collection<GameLine>): List<ChatColumn> {
        val out = mutableListOf<ChatColumn>()
        for (right in listOf(false, true)) {
            val left = spoken.sortedBy { if (right) it.bounds.right else it.bounds.left }
            val groups = mutableListOf<MutableList<GameLine>>()
            for (line in left) {
                val x = if (right) line.bounds.right else line.bounds.left
                val group = groups.lastOrNull()
                val gx = group?.let { g -> if (right) g.first().bounds.right else g.first().bounds.left }
                if (group != null && gx != null && x - gx <= line.typeHeight / 2) group += line else groups += mutableListOf(line)
            }
            for (g in groups) {
                if (g.size < MIN_COLUMN_LINES) continue
                val type = g.map { it.typeHeight }.sorted()[g.size / 2]
                val sized = g.filter { min(it.typeHeight, type).toDouble() / max(it.typeHeight, type) >= SAME_SIZE_RATIO }
                // And a sentence among them: the home screen's icon labels
                // down its left side lined up as well as any chat, and once
                // remembered as one were translated with the chat open.
                if (sized.size < MIN_COLUMN_LINES || sized.count { isSentence(it.text) } < MIN_COLUMN_SENTENCES) continue
                val edge = sized.map { if (right) it.bounds.right else it.bounds.left }.sorted()[sized.size / 2]
                out += ChatColumn(edge, right, type, sized.minOf { it.bounds.top }, sized.maxOf { it.bounds.bottom })
            }
        }
        return out
    }

    /**
     * Lines under a name that stands over two lines or more: a chat's sender,
     * 「ケイ」 over each run of her bubbles. A screen of her short replies
     * — 「せんせ」, 「た」, 「いま」, 「ああ」 — has no sentence to tell it is a
     * chat by; the name said over and over does.
     */
    fun underRepeatedNames(lines: List<GameLine>): Set<GameLine> {
        val out = mutableSetOf<GameLine>()
        for ((_, same) in lines.filter { isLabel(it.text) && !isControlWord(it.text) && it.text.none { c -> c.isDigit() } }.groupBy { it.text }) {
            if (same.size < 2) continue
            val under = same.mapNotNull { name ->
                // In type no smaller than a message's under a name: the home
                // screen's 「生徒」 at 34 px stood just under the list's last
                // entry, 40, as a message would.
                lines.filter {
                    it !== name && it.text != name.text && speaksAbove(name.bounds, it.bounds) &&
                        it.typeHeight >= name.typeHeight * COLUMN_SIZE_RATIO
                }.minByOrNull { it.bounds.top }
            }
            // One is enough with the name said twice: the chat's 「HELP!」 under
            // her second 「アリス(臨戦)」 is Latin and left out as a button's.
            if (under.isNotEmpty()) out += under
        }
        return out
    }

    /**
     * Type this close is one column's: the home screen's 「生徒」 at 34 px,
     * just under the chat list's 40 px entries and in line with them, was
     * taken into the list — and the menu bar beside it with it.
     */
    const val COLUMN_SIZE_RATIO = 0.88

    /**
     * Whether [line] has others beside it on its row, a few characters off:
     * a menu bar's items, not a chat's message, which stands alone on its
     * row of its column. The home screen's 「生徒」 under the chat list
     * lined up with the list's entries, and was taken in as one.
     */
    private fun inMenuRow(line: GameLine, lines: List<GameLine>): Boolean = lines.any { other ->
        other !== line && sameRow(other.bounds, line.bounds) &&
            min(other.typeHeight, line.typeHeight).toDouble() / max(other.typeHeight, line.typeHeight) >= SAME_SIZE_RATIO &&
            (max(other.bounds.left, line.bounds.left) - min(other.bounds.right, line.bounds.right)) in 1..line.typeHeight * MENU_GAP_LINES
    }

    private const val MENU_GAP_LINES = 4

    /** Whether, if either line is in a chat's column, both are in it. */
    private fun sameColumnIfAny(a: GameLine, b: GameLine, columns: List<ChatColumn>): Boolean {
        val column = columns.firstOrNull { it.holds(a) || it.holds(b) } ?: return true
        return column.holds(a) && column.holds(b)
    }

    /** Lines a column needs to be one, and sentences among them: the home screen's banner was one, beside its icons. */
    const val MIN_COLUMN_LINES = 3
    const val MIN_COLUMN_SENTENCES = 2

    /** How far, in lines, past the stretch it was seen in a column still reaches: a scroll brings in what was above or below. */
    const val COLUMN_SPAN_SLACK_LINES = 4

    /**
     * The speakers' names among [dialogue]: labels over what is spoken. Not
     * a chat's reply in the bubbles' column: 「はい」 is a label over the next
     * bubble too, and taken for a name it was sent as one — its translation
     * kept as a name's, and set at a name's size, smaller than the bubbles.
     */
    fun speakers(
        lines: List<GameLine>,
        places: List<TextBounds> = emptyList(),
        columns: List<ChatColumn> = emptyList(),
        /** Names seen standing over more than one run of messages lately; see [repeatedNames]. */
        senders: Set<String> = emptySet(),
        panels: Panels = ANY_PANEL,
    ): Set<GameLine> = spokenAndNames(lines, places, columns, senders, panels).second

    /** Labels standing over two runs of lines or more on this screen: a chat's senders. */
    fun repeatedNames(lines: List<GameLine>): Set<String> =
        lines.filter { isLabel(it.text) && !isControlWord(it.text) && it.text.none { c -> c.isDigit() } }
            .groupBy { it.text }
            .filter { (_, same) -> same.size >= 2 && same.count { name -> lines.any { it !== name && it.text != name.text && speaksAbove(name.bounds, it.bounds) } } >= 2 }
            .keys

    private fun spokenAndNames(
        lines: List<GameLine>,
        places: List<TextBounds>,
        columns: List<ChatColumn>,
        senders: Set<String>,
        panels: Panels,
    ): Pair<Set<GameLine>, Set<GameLine>> {
        // Or anything where dialogue has been: a game's dialogue box and its
        // name tab ([conversationPlaces]). 「うん」 says nothing a sentence
        // would, but it is a line someone speaks, in the box they speak in.
        val voiced = underRepeatedNames(lines)
        val core = lines.filter { line ->
            isSentence(line.text) || places.any { inside(line.bounds, it) } || line in voiced ||
                (!isControlWord(line.text) && columns.any { it.holds(line) })
        }.toMutableSet()
        do {
            val more = lines.filter { line ->
                line !in core && core.any { d ->
                    (sameRow(line.bounds, d.bounds) && nearAlongRow(line, d)) ||
                        // A message in a chat's column wraps within it: the
                        // home screen's 「ショップ」, just under the window
                        // and three characters to the left of the last
                        // bubble, read as its next line, and the menu bar
                        // beside it followed along its row.
                        (
                            ((wraps(line, d) && panels.joined(line, d)) || (wraps(d, line) && panels.joined(d, line))) &&
                                sameColumnIfAny(line, d, columns)
                            )
                }
            }
            core += more
        } while (more.isNotEmpty())
        // A chat's short replies — 「はい」, 「寮に着きました」 — have neither
        // a sentence's marks nor its length; they line up with the messages
        // around them, at the left edge of the left bubbles or the right edge
        // of the right ones, a column that runs from message to message.
        // By the right edge only along a column of right edges already: a
        // line set from the left ends anywhere, and the list's 「最新」 button
        // ended 15 px from where a message preview below it did.
        // Two sentences, not labels: names end where the short replies under
        // them do, and a column of 「ケイ」 and 「はい」 drew the home screen's
        // 「ショップ」 in by its right edge, and the menu bar along with it.
        val rightSet = core.filter { d ->
            isSentence(d.text) && core.any { it !== d && isSentence(it.text) && inColumnWith(it, d, byRight = true) && !inColumnWith(it, d) }
        }.toMutableSet()
        val aligned = mutableSetOf<GameLine>()
        do {
            val more = lines.filter { line ->
                line !in core && line !in aligned && !inMenuRow(line, lines) &&
                    ((core + aligned).any { d -> inColumnWith(line, d) } || rightSet.any { d -> inColumnWith(line, d, byRight = true) })
            }
            aligned += more
            rightSet += more.filter { line -> rightSet.any { d -> inColumnWith(line, d, byRight = true) } }
        } while (more.isNotEmpty())
        // And a speaker's name over any of it. Only names: spread through
        // everything above, the chat's title bar and the player's name in the
        // corner of the screen were taken for dialogue.
        val spoken = core + aligned
        val names = lines.filter { line ->
            // No digits: a name has none, and the list's title
            // 「未読メッセージ(337)」 stood over its first entry as a name does.
            line !in spoken && isLabel(line.text) && line.text.none { it.isDigit() } && spoken.any { d -> speaksAbove(line.bounds, d.bounds) }
        }
        // In a chat's column, a label is a message unless the same words stand
        // over another run of messages too: 「待ってください」, 「先生」 over the
        // next bubble were taken for names and set at a name's size.
        val repeated = repeatedNames(lines) + senders
        // Over another run in the same column, that is: a chat list's entry
        // for 「ケイ」 is one name among the list's, set like them, though she
        // stands over three runs of the chat beside it.
        fun sender(line: GameLine): Boolean {
            val column = columns.firstOrNull { it.holds(line) } ?: return true
            val same = lines.filter { it.text == line.text }
            fun inColumn(other: GameLine) = abs((if (column.rightAligned) other.bounds.right else other.bounds.left) - column.edge) <= other.typeHeight
            return if (same.size >= 2) same.count(::inColumn) >= 2 else line.text in repeated
        }
        val speakers = (spoken + names).filter { line ->
            (line.text in repeated || (line !in aligned && line !in voiced)) && isLabel(line.text) && sender(line) &&
                spoken.any { d -> d !== line && speaksAbove(line.bounds, d.bounds) }
        }.toSet()
        return (spoken + names) to speakers
    }

    private fun inColumnWith(line: GameLine, d: GameLine, byRight: Boolean = false): Boolean {
        val h = max(line.typeHeight, d.typeHeight)
        // A right edge within a character: a right bubble's text ends at a
        // narrow 「?」 in one and runs a few pixels into the bubble's tail in
        // the next — MomoTalk's 「それはよかった」 and 「どうかした?」 ended
        // 31 px apart. Left edges start at the bubble's margin, and stay
        // within half a character.
        val sameEdge = if (byRight) abs(line.bounds.right - d.bounds.right) <= h else abs(line.bounds.left - d.bounds.left) <= h / 2
        val near = abs(line.bounds.top - d.bounds.top) <= h * COLUMN_REACH_LINES
        val sameSize = min(line.typeHeight, d.typeHeight).toDouble() / h >= COLUMN_SIZE_RATIO
        return sameEdge && near && sameSize
    }

    /** How far, in line heights, a reply is looked for above or below a message. */
    const val COLUMN_REACH_LINES = 12

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
        // Never a speaker's name: on a history screen, lines set close, the
        // name 「アナウンス」 overlapped the row of the line after it and was
        // joined in front of it on some frames.
        val names = lines.filter { namesNext(it, lines) }.toSet()
        for (line in lines.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))) {
            val i = if (line !in dialogue) {
                -1
            } else {
                out.indexOfLast { before ->
                    before in dialogue && before !in names && line !in names && sameRow(before.bounds, line.bounds) &&
                        line.bounds.left >= before.bounds.right &&
                        line.bounds.left - before.bounds.right <= max(before.typeHeight, line.typeHeight) * ROW_GAP_LINES &&
                        min(before.typeHeight, line.typeHeight).toDouble() / max(before.typeHeight, line.typeHeight) >=
                        // A label beside a line must be its own size to be part of it.
                        if (isSentence(before.text) && isSentence(line.text)) SAME_ROW_SIZE_RATIO else NAME_TYPE_RATIO
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

    /**
     * A label without the stray stroke the recogniser reads off the edge of
     * its frame: Fate/Grand Order's name tab came back as 「マシュ1」 on some
     * frames, translated as 「玛修1」. Only a single 1, l, I or | standing
     * against a letter of another script is taken off — 「通行人A」 and a
     * Latin word keep everything.
     */
    fun withoutStrayStroke(text: String): String {
        if (isSentence(text)) return text
        var t = text.trim()
        fun foreign(c: Char) = c.isLetter() && c.code >= 0x250
        if (t.length >= 3 && t.last() in STRAY_STROKES && foreign(t[t.length - 2])) t = t.dropLast(1).trimEnd()
        if (t.length >= 3 && t.first() in STRAY_STROKES && foreign(t[1])) t = t.drop(1).trimStart()
        return t
    }

    private const val STRAY_STROKES = "1lI|丨｜"

    private const val SILENCE_MARKS = "…‥・.。、！？!?―ー─ "

    /**
     * Close enough along a row to be pieces of one line: a battle's 「バトル」
     * at the screen's edge shared a row with the tutorial's sentence across
     * the screen, and was taken for dialogue.
     */
    private fun nearAlongRow(a: GameLine, b: GameLine): Boolean {
        val gap = max(a.bounds.left, b.bounds.left) - min(a.bounds.right, b.bounds.right)
        return gap <= max(a.typeHeight, b.typeHeight) * ROW_GAP_LINES
    }

    /**
     * Where [lines] hold a conversation: a sentence with a speaker's name over
     * it, and the name. Remembered by the caller, these are the dialogue box
     * and the name tab, and whatever appears there later is dialogue too.
     */
    fun conversationPlaces(lines: List<GameLine>): List<TextBounds> {
        val places = mutableListOf<TextBounds>()
        for (line in lines) {
            if (!isSentence(line.text) || line.text.count { it.isLetter() } < MIN_CONVERSATION_LETTERS) continue
            val speaker = lines.firstOrNull { it !== line && isLabel(it.text) && speaksAbove(it.bounds, line.bounds) } ?: continue
            places += line.bounds
            places += speaker.bounds
        }
        return places
    }

    /** Most of [line] within [place], with half a line of slack. */
    private fun inside(line: TextBounds, place: TextBounds): Boolean {
        val slack = place.height / 2
        val w = min(line.right, place.right + slack) - max(line.left, place.left - slack)
        val h = min(line.bottom, place.bottom + slack) - max(line.top, place.top - slack)
        return w > 0 && h > 0 && w.toLong() * h * 2 >= line.width.toLong() * line.height
    }

    /** A sentence's punctuation, or more letters than a label has. */
    fun isSentence(text: String): Boolean =
        // A badge's 「開催中!」 has a sentence's mark and a label's length.
        // Nor a label with a comma read after it: 「お知らせ、」, the home
        // screen's notice button with a speck the recogniser took for 「、」.
        (punctuated(text) && text.count { it.isLetter() } >= MIN_SENTENCE_LETTERS && !strayComma(text)) ||
            // A question: 「バグ？」 is asked, and no interface word asks.
            ((text.contains('?') || text.contains('？')) && text.count { it.isLetter() } >= 2) ||
            // Trailing off: 「はああ……」, 「くっ……」 are a breath, not a label —
            // no interface word is set with an ellipsis after two letters.
            (ELLIPSES.any { it in text } && text.count { it.isLetter() } >= 2) ||
            // Long, but without punctuation and with a space between words of
            // a script written without spaces: two labels side by side —
            // Blue Archive's tiles 「戦術対抗戦 制約解除決戦」 read as one line.
            (text.count { it.isLetter() } > MAX_LABEL_LETTERS && !spacedLabels(text))

    /**
     * A sentence's punctuation. A quotation's bracket counts only with its
     * pair: one 「 was read off the edge of a tile and made two of Blue
     * Archive's buttons a sentence.
     */
    private fun punctuated(text: String): Boolean =
        text.any { it in SENTENCE_MARKS && it !in BRACKETS } ||
            ('「' in text && '」' in text) || ('『' in text && '』' in text)

    /** A short word whose only mark is a comma after it. */
    private fun strayComma(text: String): Boolean {
        val t = text.trimEnd()
        if (t.isEmpty() || t.last() !in "、,，") return false
        val body = t.dropLast(1)
        return !punctuated(body) && body.count { it.isLetter() } <= MAX_STRAY_COMMA_LETTERS
    }

    private const val MAX_STRAY_COMMA_LETTERS = 6

    private const val BRACKETS = "「」『』"
    private val ELLIPSES = listOf("…", "‥", "...")

    /** A name or an interface word: no sentence's mark and not a sentence's length. */
    fun isLabel(text: String): Boolean = !punctuated(text) && !isSentence(text)

    private fun spacedLabels(text: String): Boolean {
        val t = text.trim()
        return t.indices.any { i ->
            t[i].isWhitespace() && i > 0 && i < t.length - 1 &&
                t[i - 1].let { it.isLetter() && it.code >= 0x2E80 } && t[i + 1].let { it.isLetter() && it.code >= 0x2E80 }
        }
    }

    const val SENTENCE_MARKS = "。、？！?!…「」『』"

    /**
     * Letters a punctuated line needs to be a sentence on its own. Shorter
     * replies — 「うん。」 — are dialogue by where they are ([dialogue]'s places).
     */
    const val MIN_SENTENCE_LETTERS = 4

    /** Letters a line under a name needs before the place counts as a conversation. */
    const val MIN_CONVERSATION_LETTERS = 6

    /** Longer than this without punctuation is still a sentence, not a label. */
    const val MAX_LABEL_LETTERS = 12

    /**
     * A game's own controls written in another script than the game — AUTO,
     * MENU, SKIP and LOG in a Japanese game — left as they are. They are words
     * every player of the game already reads, a chat model asked about one
     * word alone guessed wrong (AUTO came back as "car"), and a translation
     * over them stands between the player and the button.
     *
     * Both of: Latin letters only, one word, anywhere on screen; and on
     * a frame whose text is mostly in another script. In an English game the
     * same words are the game's language and are translated, and a choice or
     * a menu entry, set in the game's own script, never qualifies.
     */
    fun controls(lines: List<GameLine>, frameHeight: Int, frameWidth: Int, otherScript: Boolean? = null): Set<GameLine> {
        val candidates = lines.filter { line ->
            val word = isControlWord(line.text)
            val b = line.bounds
            // Or a name in Latin letters over a line of dialogue: in a game
            // written in another script that is a name the player typed —
            // 「yizhiteamo」 came back as 「一支队伍」, "a team".
            val name = isLatinName(line.text) && lines.any { it !== line && isSentence(it.text) && speaksAbove(b, it.bounds) }
            // Anywhere, not only at the edge: a map's 「Touch」 by its marker
            // flickered between itself and 「触摸」 as the arrow beside it moved.
            word || name
        }
        if (candidates.isEmpty()) return emptySet()
        // Not yet known — a fade-in showing only 「SKIP」 — counts as another
        // script: translated then, the word stayed translated (「跳过」) for the
        // whole scene. An English game has its controls translated a moment
        // later, once there is text enough to tell.
        // The game's script as remembered over many frames comes first: one
        // frame of 「Gi——GAAAAAAAAAAAA！」 read as a Latin game and translated
        // 「SKIP」 for a moment.
        val inOtherScript = otherScript ?: writtenInOtherScript(lines - candidates.toSet()) ?: true
        return if (inOtherScript) candidates.toSet() else emptySet()
    }

    /**
     * Whether [lines] are mostly in a script other than Latin, or null when
     * there is too little text to say — a frame showing only AUTO and MENU.
     * The caller remembers the last answer for such frames.
     */
    fun writtenInOtherScript(all: List<GameLine>): Boolean? {
        // A control's own word does not say what the game is written in.
        // Nor does a name the player typed in Latin letters: 「yizhiteamo」
        // over a line of 「……！」 made a Japanese game read as Latin.
        val lines = all.filterNot { isLatinName(it.text) }
        val letters = lines.sumOf { line -> line.text.count { it.isLetter() } }
        if (letters < MIN_SCRIPT_LETTERS) return null
        val latin = lines.sumOf { line -> line.text.count(::isLatinLetter) }
        return latin * 2 < letters
    }

    /** Latin letters and other letters on [all], for a vote kept across frames; see [writtenInOtherScript]. */
    fun scriptLetters(all: List<GameLine>): Pair<Int, Int> {
        val lines = all.filterNot { isLatinName(it.text) }
        val latin = lines.sumOf { line -> line.text.count(::isLatinLetter) }
        val other = lines.sumOf { line -> line.text.count { it.isLetter() && !isLatinLetter(it) } }
        return latin to other
    }

    private fun isLatinLetter(c: Char): Boolean = c.isLetter() && c.code < 0x250

    /**
     * One Latin word, with at most the arrows or marks a button draws beside
     * it: Fate/Grand Order's 「SKIP▶▶」 read as 「SKIP»」 on some frames, and
     * those frames translated it.
     */
    private fun isLatinName(text: String): Boolean {
        val letters = text.filter { it.isLetterOrDigit() }
        return letters.length in 2..MAX_LATIN_NAME && letters.all(::isLatinLetter) && ' ' !in text.trim()
    }

    /** A player's name: Fate/Grand Order allows twelve characters. */
    private const val MAX_LATIN_NAME = 16

    private fun isControlWord(text: String): Boolean {
        val letters = text.filter { it.isLetterOrDigit() }
        // A space inside is allowed — 「To uch」 was read on the frames where a
        // highlight crossed the word — as long as it is a word's worth of letters.
        // Mostly Latin is enough: an icon read as a character in front of the
        // word — 「食MomoTalk ?」 — made a title bar a sentence.
        val latin = letters.count(::isLatinLetter)
        return latin in 2..MAX_CONTROL_LETTERS && latin * 4 >= letters.length * 3 && text.trim().count { it == ' ' } <= 1
    }

    /** Letters needed on a frame to tell which script a game is in. */
    const val MIN_SCRIPT_LETTERS = 6

    /** A control's word is at most this long: 「SHATTERED」 is nine. */
    const val MAX_CONTROL_LETTERS = 12

    
    /**
     * How far over the backdrop threshold a speaker label may be. The name row
     * sits on the panel's translucent edge with a character behind it:
     * measured 15 (通行人A) and 31 (ソラ, エンジェル24).
     */
    const val SPEAKER_BACKDROP_ALLOWANCE = 4.0

    /** A line this wide of the frame is dialogue, not lettering on the art. */
    const val LONG_LINE_FRACTION = 0.4
    const val LONG_LINE_ALLOWANCE = 2.0

    /**
     * Where [selected] has a speaker's name: a label kept above dialogue. A
     * game sets every name in the same tab, so a name found there once marks
     * the place, and a longer name over a busier part of the art behind the
     * tab is still a name: 「Dr.ロマン」 scored 32 where 「マシュ」 scored 13.
     */
    fun speakerSpots(selected: List<GameLine>, frameHeight: Int): List<TextBounds> =
        selected.filter { label ->
            GameTextFilter.judge(label, frameHeight) == GameTextFilter.Verdict.ON_ARTWORK &&
                selected.any { it !== label && isSentence(it.text) && speaksAbove(label.bounds, it.bounds) }
        }.map { it.bounds }

    private fun at(spot: TextBounds, b: TextBounds): Boolean =
        abs(spot.left - b.left) <= b.height / 2 && abs(spot.top - b.top) <= b.height / 2

    /** Whether [label] sits where a speaker's name does, just above and over the start of [line]. */
    fun speaksAbove(label: TextBounds, line: TextBounds): Boolean {
        val gap = line.top - label.bottom
        // Beside the name too: an affiliation is to its right, on the same row,
        // and the dialogue starts under the name, not under it.
        // And at most a little out to the left of it: a game sets its name tab
        // that way. How far out is too far depends on the backdrop ([select]).
        // The dialogue may start a little after the name ends: a line opening
        // with 「 or …… the recogniser left out began 74 px past the name.
        return gap in 0..label.height * 2 && line.left <= label.right + label.height * 2 &&
            label.left - line.left in -label.height * 4..label.height * 4
    }
}
