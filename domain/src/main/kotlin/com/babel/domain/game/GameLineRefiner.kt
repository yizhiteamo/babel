package com.babel.domain.game

import com.babel.core.model.TextBounds
import com.babel.domain.game.GameFrameLook.INK_CONTRAST
import com.babel.domain.game.GameLineStyle.sameRow
import com.babel.domain.game.GamePanelSampler.MID_LUMA
import com.babel.domain.game.GamePixels.luma
import com.babel.domain.game.GameStyleMemory.Companion.FULL_WIDTH_FROM
import com.babel.domain.game.GameStyleMemory.Companion.FULL_WIDTH_MARKS
import com.babel.domain.game.GameStyleMemory.Companion.HALF_WIDTH
import com.babel.domain.game.GameStyleMemory.Companion.SAME_TYPE_RATIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Project-owned single recognised character/symbol from OCR, free of ML Kit types. */
data class RawSymbol(
    val text: String,
    val bounds: TextBounds?,
)

/** Project-owned word/element within a recognised OCR line, free of ML Kit types. */
data class RawElement(
    val text: String,
    val bounds: TextBounds?,
    val symbols: List<RawSymbol>,
)

/** Project-owned recognised OCR line in frame coordinates, free of ML Kit types. */
data class RawRecognisedLine(
    val text: String,
    val bounds: TextBounds?,
    val elements: List<RawElement>,
)

/**
 * Post-OCR splitting, ornament/tail stripping, and fragment-joining rules for
 * recognised game lines.
 */
class GameLineRefiner {

    companion object {
        /** Elements of one recognised line this far apart, against their height, are two pieces of text. */
        const val WORD_GAP_OF_HEIGHT = 0.6
        const val SYMBOL_GAP_OF_HEIGHT = 0.7
        const val WORD_PIECE_GAP = 0.4

        /** Where Japanese script starts in Unicode (CJK punctuation, kana, Han). */
        const val JAPANESE_FROM = 0x3000

        /** How much of the line's end, against its height, a last mark is looked for in: about one narrow glyph. */
        const val TAIL_WIDTH_OF_HEIGHT = 0.4
        const val TAIL_MARKS = "?？>〉"

        /**
         * Less type than this in the last mark's width, and the mark was not
         * there. Measured: a tail read as a mark 0.2%; a real 「？」 the box
         * ended inside of 0.7–10%, and at 5% 「バグ？」 lost its question.
         */
        const val TAIL_MAX_INK = 0.01

        /** More ink than this in the last mark's width is a filled button; a 「？」 glyph is mostly panel. */
        const val BUTTON_INK = 0.5

        /** No ink for this much of the type's height inside a label is between two of them. */
        const val INK_GAP_OF_TYPE = 1.0
        const val MIN_GAP_SPLIT_LETTERS = 5

        private fun centerY(b: TextBounds): Int = (b.top + b.bottom) shr 1

        private fun union(a: TextBounds, b: TextBounds): TextBounds {
            if (b.isEmpty) return a
            if (a.isEmpty) return b
            return TextBounds(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom), a.space)
        }

        /**
         * [lines] with two pieces of one word joined: on a row, of one size, no
         * more than 0.4 of the type's height apart. The recogniser came back with
         * 「エピソー」 and 「ド情報」 22 px apart on a 61 px tab, and the tab read
         * 「插曲 ド情報」, half translated. Words set side by side as separate
         * labels stand a glyph or more apart (a skill card's, two titles').
         */
        fun withWordPiecesJoined(lines: List<GameLine>): List<GameLine> {
            val out = lines.toMutableList()
            var i = 0
            while (i < out.size) {
                val a = out[i]
                val b = out.firstOrNull { o ->
                    o !== a && sameRow(o.bounds, a.bounds) && o.bounds.left >= a.bounds.right - a.typeHeight / 4 &&
                        o.bounds.left - a.bounds.right <= max(a.typeHeight, o.typeHeight) * WORD_PIECE_GAP &&
                        min(o.typeHeight, a.typeHeight).toDouble() / max(o.typeHeight, a.typeHeight) >= SAME_TYPE_RATIO &&
                        GameLayout.isLabel(a.text) && GameLayout.isLabel(o.text) &&
                        a.text.any { it.code >= JAPANESE_FROM } && o.text.any { it.code >= JAPANESE_FROM }
                }
                if (b == null) {
                    i++
                    continue
                }
                out[i] = a.copy(
                    text = a.text.trimEnd() + b.text.trimStart(),
                    bounds = TextBounds(a.bounds.left, min(a.bounds.top, b.bounds.top), b.bounds.right, max(a.bounds.bottom, b.bounds.bottom), a.bounds.space),
                    backdropError = max(a.backdropError, b.backdropError),
                    typeHeight = max(a.typeHeight, b.typeHeight),
                )
                out.remove(b)
            }
            return out
        }

        /**
         * [lines] with each bracketed part joined to the word before it on its
         * row. A full-width bracket stands half a glyph off the word, and
         * 「ハナコ(水着)」 came back as 「ハナコ」 and 「(水着)」: the name was
         * translated alone, drawn at the left of its place, and the kanji part
         * left in Japanese after a gap — 「花子　(水着)」 beside 「日奈(礼服)」,
         * read as one, in the next card.
         */
        fun withBracketedParts(lines: List<GameLine>): List<GameLine> {
            val out = lines.toMutableList()
            for (part in lines) {
                val t = part.text.trim()
                if (t.length < 3 || t.first() !in "(（" || t.last() !in ")）") continue
                val word = out.filter { it !== part && sameRow(it.bounds, part.bounds) && it.bounds.right <= part.bounds.left + part.typeHeight / 2 }
                    .maxByOrNull { it.bounds.right }
                    ?.takeIf { part.bounds.left - it.bounds.right <= max(it.typeHeight, part.typeHeight) && it.text.none { c -> c in "(（" } }
                    ?: continue
                val b = word.bounds
                val joined = word.copy(
                    text = word.text.trimEnd() + t,
                    bounds = TextBounds(b.left, min(b.top, part.bounds.top), part.bounds.right, max(b.bottom, part.bounds.bottom), b.space),
                    backdropError = max(word.backdropError, part.backdropError),
                    // The word's type: brackets stand taller than the letters.
                    typeHeight = word.typeHeight,
                )
                out[out.indexOf(word)] = joined
                out.remove(part)
            }
            return out
        }

        /**
         * One recognised line, cut where its words stand far apart.
         *
         * ML Kit groups words by height, so two buttons on one row — AUTO and MENU,
         * 120 px apart — come back as one line on some frames and two on others.
         * Each flip read as a new line, withdrew the translation and drew it again
         * (`StableLines`); a gap wider than the line's height is never inside a word.
         */
        fun separated(line: RawRecognisedLine): List<Pair<String, TextBounds>> {
            val box = line.bounds ?: return emptyList()
            atIconSymbols(line)?.let { return it }
            atSymbolGaps(line)?.let { return it }
            val elements = line.elements.filter { it.bounds != null }
            if (elements.size < 2) return listOf(line.text to box)
            val groups = mutableListOf(mutableListOf(elements.first()))
            for (e in elements.drop(1)) {
                val last = groups.last().last().bounds!!
                val now = e.bounds!!
                val gap = now.left - last.right
                // Apart, or on another baseline: a carousel's front title and the
                // one of the card behind it, 「第2部メインストーリー」 and 「トリー」,
                // came back as one line whose box spanned both cards, and the
                // translation was set across both, the next card's word in it.
                // Measured: 66 px between the two titles, glyphs of one word
                // 10–25 apart, and the line's box 101 tall around both.
                val h = min(last.height, now.height)
                // The tighter gap only for titles: a sentence's elements stand
                // apart at its commas and quotes, and cut there a story's line was
                // translated in pieces with the original showing between them.
                val limit = if (line.text.any { it in GameLayout.SENTENCE_MARKS }) box.height else min(box.height, (h * WORD_GAP_OF_HEIGHT).toInt())
                if (gap > limit || abs(centerY(now) - centerY(last)) > h / 3) groups += mutableListOf(e) else groups.last() += e
            }
            // A group with no letters — an opening bracket or quote set apart —
            // belongs to its neighbour, not on its own.
            var i = 0
            while (i < groups.size && groups.size > 1) {
                if (groups[i].none { e -> e.text.any(Char::isLetterOrDigit) }) {
                    val into = if (i + 1 < groups.size) i + 1 else i - 1
                    if (into > i) groups[into].addAll(0, groups[i]) else groups[into].addAll(groups[i])
                    groups.removeAt(i)
                } else {
                    i++
                }
            }
            if (groups.size == 1) return listOf(line.text to box)
            return groups.map { group ->
                var rect = group.first().bounds!!
                group.forEach { rect = union(rect, it.bounds!!) }
                group.joinToString(" ") { it.text } to rect
            }
        }

        /**
         * A line cut where its characters stand far apart, measured symbol by
         * symbol; null when none do. The recogniser can put a carousel's front
         * title and the end of the one behind it in a single element —
         * 「第2部メインストーリートリー」 on one frame, two elements on another —
         * and the element gap alone missed it. Glyphs of one word stand 10–25 px
         * apart at 76 px; the two titles 66.
         */
        fun atSymbolGaps(line: RawRecognisedLine): List<Pair<String, TextBounds>>? {
            val symbols = line.elements.flatMap { it.symbols }.filter { it.bounds != null }
            if (symbols.size < 4 || line.text.any { it in GameLayout.SENTENCE_MARKS }) return null
            val pieces = mutableListOf(mutableListOf(symbols.first()))
            for (sym in symbols.drop(1)) {
                val last = pieces.last().last().bounds!!
                val now = sym.bounds!!
                val h = min(last.height, now.height)
                if (h > 0 && now.left - last.right > h * SYMBOL_GAP_OF_HEIGHT) pieces += mutableListOf(sym) else pieces.last() += sym
            }
            // Each piece a word of two letters or more: a label set wide —
            // 「サポータ」 and its 「ー」 stretched to the box's end — is one word.
            if (pieces.size < 2 || pieces.any { p -> p.sumOf { sym -> sym.text.count { it.isLetterOrDigit() && it != 'ー' } } < 2 }) return null
            return pieces.map { p ->
                var rect = p.first().bounds!!
                p.forEach { rect = union(rect, it.bounds!!) }
                p.joinToString("") { it.text } to rect
            }
        }

        /**
         * A line cut at icons the recogniser read as characters inside it, each
         * piece boxed by its own characters; null when there are none. A
         * student's 「(臨戦)」, her rarity stars and her 「STRIKER」 badge came back
         * as one line, 「(臨戦)★食★STRIKE」, and the bracketed part was never
         * joined to the name before it.
         */
        fun atIconSymbols(line: RawRecognisedLine): List<Pair<String, TextBounds>>? {
            val first = line.text.indexOfFirst(::isIcon)
            if (first <= 0) return null
            class Piece(val text: StringBuilder, var bounds: TextBounds)
            val pieces = mutableListOf<Piece>()
            var open: Piece? = null
            for (element in line.elements) {
                var newElement = true
                for (symbol in element.symbols) {
                    val box = symbol.bounds ?: continue
                    if (symbol.text.any(::isIcon)) {
                        open = null
                        continue
                    }
                    val current = open ?: Piece(StringBuilder(), box).also { pieces += it; open = it }
                    // Latin words keep the space the recogniser put between them.
                    if (newElement && current.text.lastOrNull()?.let { it.code < 0x80 && it.isLetterOrDigit() } == true && symbol.text.first().code < 0x80) current.text.append(' ')
                    newElement = false
                    current.text.append(symbol.text)
                    current.bounds = union(current.bounds, box)
                }
            }
            val kept = pieces.filter { it.text.any(Char::isLetterOrDigit) }
            return kept.map { it.text.toString() to it.bounds }.takeIf { it.isNotEmpty() }
        }

        /**
         * A label cut where its ink stops for longer than a word ever does: two
         * tabs' names side by side — 「ノーマルスキル」 and 「パッシブスキル+」 — read
         * as one run of letters, translated as one, and set squeezed across both
         * tabs and the border between them. Glyphs of a word stand a fraction of
         * the type apart; [INK_GAP_OF_TYPE] of it, inked nowhere, is between
         * words. Each piece takes the letters its share of the width holds.
         */
        fun atInkGaps(frame: FramePixels, line: GameLine): List<GameLine> {
            // Never a sentence: words in a line of dialogue stand apart too. Nor
            // Latin words, spaced as a sentence's are. A space between Japanese
            // words is two labels: 「ノーマルスキル+ パッシブスキル+」, two skill
            // cards, came back one line and was drawn as one across both.
            val latinSpace = line.text.indices.any { i -> line.text[i] == ' ' && (line.text.getOrNull(i - 1)?.code ?: 0) < 0x80 && (line.text.getOrNull(i + 1)?.code ?: 0) < 0x80 }
            // Two marked labels read as one, a stray 「!」 at the end: 「ノーマルスキル+
            // パッシブスキル+!」 was taken for a sentence and translated as one,
            // with a character's name in it. A 「!」 after a 「+」 is no sentence's.
            val marks = line.text.trimEnd().let { t -> if (Regex("[+＋][!！]$").containsMatchIn(t)) t.dropLast(1) else t }
            if (line.text.length < MIN_GAP_SPLIT_LETTERS || latinSpace || marks.any { it in GameLayout.SENTENCE_MARKS }) return listOf(line)
            val b = line.bounds
            if (b.left < 0 || b.right > frame.width || b.top < 0 || b.bottom > frame.height || b.width < 8) return listOf(line)
            val h = b.height
            val column = IntArray(h)
            val middle = IntArray(b.width).also { frame.getPixels(it, 0, b.width, b.left, b.top + h / 2, b.width, 1) }
                .map { luma(it) }.sorted()[b.width / 2]
            val inked = BooleanArray(b.width) { x ->
                frame.getPixels(column, 0, 1, b.left + x, b.top, 1, h)
                column.count { abs(luma(it) - middle) >= INK_CONTRAST } >= 2
            }
            val minGap = (line.typeHeight * INK_GAP_OF_TYPE).toInt().coerceAtLeast(4)
            val first = inked.indexOfFirst { it }
            val last = inked.indexOfLast { it }
            if (first < 0) return listOf(line)
            val cuts = mutableListOf<Pair<Int, Int>>()
            var x = first
            while (x <= last) {
                if (inked[x]) { x++; continue }
                val start = x
                while (x <= last && !inked[x]) x++
                if (x - start >= minGap) cuts += start to x
            }
            if (cuts.isEmpty()) return listOf(line)
            // Letters by width: each piece gets the characters its ink spans.
            val inkWidth = (last - first + 1).toDouble()
            val n = line.text.length
            val edges = listOf(first) + cuts.flatMap { listOf(it.first, it.second) } + listOf(last + 1)
            val out = mutableListOf<GameLine>()
            var from = 0
            for (i in edges.indices step 2) {
                val l = edges[i]
                val r = edges[i + 1]
                val to = if (i + 2 >= edges.size) n else (n * ((r - first + (edges.getOrNull(i + 2) ?: r) - first) / 2.0) / inkWidth).roundToIntSafe().coerceIn(from + 1, n)
                // A divider between the two read as a stroke goes with neither.
                val text = GameLayout.withoutStrayStroke(line.text.substring(from, to).trim())
                from = to
                if (text.none(Char::isLetterOrDigit)) continue
                out += line.copy(text = text, bounds = TextBounds(b.left + l, b.top, b.left + r, b.bottom, b.space))
            }
            return if (out.size >= 2) out else listOf(line)
        }

        private fun Double.roundToIntSafe(): Int = kotlin.math.round(this).toInt()

        /**
         * A line cut where icons read as characters stand inside it: a student's
         * 「(臨戦)」, her rarity stars and her 「STRIKER」 badge came back as one
         * line, 「(臨戦)★食★STRIKE」, and the bracketed part was never joined to
         * the name before it. The letters before the icons keep the width their
         * characters take, full or half, to the column with the least ink near
         * there; what follows the icons goes with them.
         */
        fun atIcons(frame: FramePixels, line: GameLine): List<GameLine> {
            val t = line.text
            val first = t.indexOfFirst(::isIcon)
            if (first <= 0 || t.substring(0, first).none(Char::isLetter)) return listOf(line)
            val b = line.bounds
            if (b.left < 0 || b.right > frame.width || b.top < 0 || b.bottom > frame.height) return listOf(line)
            fun units(text: String) = text.sumOf { c -> if (c.isWhitespace()) 0.0 else if (c.code >= FULL_WIDTH_FROM || c in FULL_WIDTH_MARKS || isIcon(c)) 1.0 else HALF_WIDTH }
            val em = b.width / units(t).coerceAtLeast(1.0)
            val estimate = b.left + (em * units(t.substring(0, first))).toInt()
            val column = IntArray(b.height)
            val middle = IntArray(b.width).also { frame.getPixels(it, 0, b.width, b.left, b.top + b.height / 2, b.width, 1) }
                .map { luma(it) }.sorted()[b.width / 2]
            val reach = (em / 2).toInt().coerceAtLeast(1)
            val end = ((estimate - reach).coerceAtLeast(b.left + 1)..(estimate + reach).coerceAtMost(b.right - 1)).minByOrNull { x ->
                frame.getPixels(column, 0, 1, x, b.top, 1, b.height)
                column.count { abs(luma(it) - middle) >= INK_CONTRAST } * 4 + abs(x - estimate) / reach.toDouble()
            } ?: return listOf(line)
            return listOf(GameLine(t.substring(0, first).trim(), TextBounds(b.left, b.top, end, b.bottom, b.space), line.backdropError, line.typeHeight))
        }

        /**
         * A line cut where the recogniser read a divider as a bar: two tabs side
         * by side came back as 「ノーマルスキル||バッシブスキル+」 on one frame and
         * as one run of letters on the next, and the two readings took turns.
         * Each piece gets its share of the box by its characters.
         */
        fun atDividers(line: GameLine): List<GameLine> {
            val pieces = line.text.split('|', '｜').map { it.trim() }.filter { it.any(Char::isLetter) }
            if (pieces.size < 2 || pieces.any { GameLayout.isSentence(it) }) return listOf(line)
            val total = line.text.length.toDouble()
            val out = mutableListOf<GameLine>()
            var at = 0
            for (piece in pieces) {
                val start = line.text.indexOf(piece, at).coerceAtLeast(at)
                val end = start + piece.length
                at = end
                val l = line.bounds.left + (line.bounds.width * start / total).toInt()
                val r = line.bounds.left + (line.bounds.width * end / total).toInt()
                out += line.copy(text = GameLayout.withoutStrayStroke(piece), bounds = TextBounds(l, line.bounds.top, r, line.bounds.bottom, line.bounds.space))
            }
            return out
        }

        fun isIcon(c: Char): Boolean = c in '①'..'⓿' || c in '■'..'◿' || c in '☀'..'⛿' ||
            c in '⊕'..'⊙' || c in '❤'..'❥' || c in '㉑'..'㉟' || c in '㊱'..'㊿'

        /**
         * [text] without a last mark that was never there: the tail of a chat
         * bubble the text nearly fills. MomoTalk's 「それはよかった」 was read now
         * and then as 「それはよかった?」 — the box the same, its end on the
         * bubble's edge and the pointed tail past it — and translated as a
         * question. A real 「?」 has type in the line's last glyph's width; there
         * a read mark has nothing but the page running in from the box's end.
         */
        fun withoutTail(frame: FramePixels, text: String, box: TextBounds): Pair<String, TextBounds> {
            val trimmed = text.trimEnd()
            val last = trimmed.lastOrNull() ?: return text to box
            if (last !in TAIL_MARKS || trimmed.length < 2) return text to box
            val tail = (box.height * TAIL_WIDTH_OF_HEIGHT).toInt().coerceAtMost(box.width / 3)
            if (tail < 2 || box.left < 0 || box.right > frame.width || box.top < 2 || box.bottom + 1 >= frame.height) return text to box
            // The panel as the middle of everything in the line before its end:
            // type is the lesser part of a line's area. Not just above and below
            // it — a bubble the text nearly fills has the page there, its white
            // type read as no ink, and a real 「？」 in 「バグ？」 was taken for a tail.
            val body = IntArray(box.width - tail)
            val lumas = mutableListOf<Double>()
            for (y in box.top until box.bottom step 2) {
                frame.getPixels(body, 0, body.size, box.left, y, body.size, 1)
                for (x in body.indices step 2) lumas += luma(body[x])
            }
            val panel = lumas.sorted()[lumas.size / 2]
            val sign = if (panel < MID_LUMA) 1.0 else -1.0
            val row = IntArray(tail)
            var ink = 0
            var filled = 0
            for (y in box.top until box.bottom) {
                frame.getPixels(row, 0, tail, box.right - tail, y, tail, 1)
                val inked = BooleanArray(tail) { (luma(row[it]) - panel) * sign >= INK_CONTRAST }
                filled += inked.count { it }
                // Not what runs in from the box's end: the page past the bubble.
                var end = tail
                while (end > 0 && inked[end - 1]) end--
                for (x in 0 until end) if (inked[x]) ink++
            }
            val share = ink.toDouble() / (tail * box.height)
            // Filled: a help button, a dark square with a light 「?」 in it, read
            // as the word's question. 「生徒?」 over the student list was taken
            // for a sentence and translated 「学生?|」. The button is not the
            // word's, so its place leaves the box.
            if (filled.toDouble() / (tail * box.height) >= BUTTON_INK && last in "?？") {
                val column = IntArray(box.height)
                var x = box.right - 1
                while (x > box.left + box.width / 2) {
                    frame.getPixels(column, 0, 1, x, box.top, 1, box.height)
                    if (column.count { (luma(it) - panel) * sign >= INK_CONTRAST } * 2 < box.height) break
                    x--
                }
                // Past the button's own edge, and the gap before it.
                while (x > box.left + box.width / 2) {
                    frame.getPixels(column, 0, 1, x, box.top, 1, box.height)
                    if (column.any { (luma(it) - panel) * sign >= INK_CONTRAST }) break
                    x--
                }
                return trimmed.dropLast(1).trimEnd() to TextBounds(box.left, box.top, x + 1, box.bottom, box.space)
            }
            if (share >= TAIL_MAX_INK) return text to box
            return trimmed.dropLast(1).trimEnd() to box
        }
    }
}
