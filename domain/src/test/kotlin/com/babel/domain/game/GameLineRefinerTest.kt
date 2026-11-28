package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLineRefinerTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        TextBounds(left, top, right, bottom, CoordinateSpace.SCREEN)

    private fun grey(level: Int): Int {
        val c = level.coerceIn(0, 255)
        return GamePixels.rgb(c, c, c)
    }

    private fun frameOf(width: Int, height: Int, pixelAt: (x: Int, y: Int) -> Int): ArrayFramePixels {
        val data = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = pixelAt(x, y)
            }
        }
        return ArrayFramePixels(width, height, data)
    }

    private fun elementOf(text: String, left: Int, top: Int, right: Int) = RawElement(
        text = text,
        bounds = box(left, top, right, top + 30),
        symbols = text.mapIndexed { idx, c ->
            val w = (right - left) / text.length.coerceAtLeast(1)
            RawSymbol(c.toString(), box(left + idx * w, top, left + (idx + 1) * w, top + 30))
        },
    )

    @Test
    fun `separated splits distant or baseline-shifted elements merges punctuation groups and keeps sentences whole`() {
        // Two distant buttons on one row (gap 120 > min(30, 18)).
        val auto = elementOf("AUTO", 40, 20, 120)
        val menu = elementOf("MENU", 240, 20, 320)
        val buttons = RawRecognisedLine("AUTO MENU", box(40, 20, 320, 50), listOf(auto, menu))
        assertEquals(
            listOf("AUTO" to box(40, 20, 120, 50), "MENU" to box(240, 20, 320, 50)),
            GameLineRefiner.separated(buttons),
        )

        // Baseline-shifted rear carousel fragment (centerY difference 14 > h / 3 = 10).
        val front = elementOf("メイン", 40, 20, 130)
        val rear = elementOf("トリー", 136, 34, 220)
        val shifted = RawRecognisedLine("メイントリー", box(40, 20, 220, 64), listOf(front, rear))
        assertEquals(
            listOf("メイン" to box(40, 20, 130, 50), "トリー" to box(136, 34, 220, 64)),
            GameLineRefiner.separated(shifted),
        )

        // Opening quote with no alphanumeric characters merges into the following word instead of standing alone.
        val quote = RawElement("「", box(20, 20, 32, 50), listOf(RawSymbol("「", box(20, 20, 32, 50))))
        val word = elementOf("先生", 60, 20, 120)
        val quoted = RawRecognisedLine("「 先生", box(20, 20, 120, 50), listOf(quote, word))
        assertEquals(listOf("「 先生" to box(20, 20, 120, 50)), GameLineRefiner.separated(quoted))

        // Sentence containing punctuation uses full box.height (30) as gap limit rather than 0.6 * h (18).
        val clauseA = elementOf("こんにちは、", 20, 20, 160)
        val clauseB = elementOf("先生。", 184, 20, 260) // gap = 24 (18 < 24 <= 30)
        val sentence = RawRecognisedLine("こんにちは、先生。", box(20, 20, 260, 50), listOf(clauseA, clauseB))
        assertEquals(listOf("こんにちは、先生。" to box(20, 20, 260, 50)), GameLineRefiner.separated(sentence))
    }

    @Test
    fun `atSymbolGaps splits single-element symbol jumps while preserving stretched katakana labels`() {
        val frontSymbols = listOf(
            RawSymbol("ス", box(20, 20, 44, 60)),
            RawSymbol("ト", box(46, 20, 70, 60)),
            RawSymbol("リ", box(72, 20, 96, 60)),
        )
        // 60-px jump (> 40 * SYMBOL_GAP_OF_HEIGHT = 28) to rear card's 2-letter fragment.
        val rearSymbols = listOf(
            RawSymbol("ト", box(156, 20, 180, 60)),
            RawSymbol("リ", box(182, 20, 206, 60)),
        )
        val oneElement = RawElement("ストリトリ", box(20, 20, 206, 60), frontSymbols + rearSymbols)
        val line = RawRecognisedLine("ストリトリ", box(20, 20, 206, 60), listOf(oneElement))
        assertEquals(
            listOf("ストリ" to box(20, 20, 96, 60), "トリ" to box(156, 20, 206, 60)),
            GameLineRefiner.atSymbolGaps(line),
        )

        // Stretched katakana label ending in a distant 'ー' (< 2 non-'ー' letters in second piece) stays whole.
        val stretched = RawElement(
            "サポーター",
            box(20, 20, 200, 60),
            listOf(
                RawSymbol("サ", box(20, 20, 44, 60)),
                RawSymbol("ポ", box(46, 20, 70, 60)),
                RawSymbol("ー", box(72, 20, 96, 60)),
                RawSymbol("タ", box(98, 20, 122, 60)),
                RawSymbol("ー", box(170, 20, 200, 60)),
            ),
        )
        assertNull(GameLineRefiner.atSymbolGaps(RawRecognisedLine("サポーター", box(20, 20, 200, 60), listOf(stretched))))
    }

    @Test
    fun `atIconSymbols cuts around interior icon symbols and preserves spaces between Latin words`() {
        val e1 = RawElement(
            "(臨戦)★■",
            box(20, 20, 140, 50),
            listOf(
                RawSymbol("(", box(20, 20, 30, 50)),
                RawSymbol("臨", box(30, 20, 55, 50)),
                RawSymbol("戦", box(55, 20, 80, 50)),
                RawSymbol(")", box(80, 20, 90, 50)),
                RawSymbol("★", box(95, 20, 115, 50)),
                RawSymbol("■", box(118, 20, 140, 50)),
            ),
        )
        val e2 = elementOf("STRIKER", 145, 20, 230)
        val e3 = elementOf("UNIT", 240, 20, 300)
        val line = RawRecognisedLine("(臨戦)★■STRIKER UNIT", box(20, 20, 300, 50), listOf(e1, e2, e3))

        assertEquals(
            listOf("(臨戦)" to box(20, 20, 90, 50), "STRIKER UNIT" to box(145, 20, 300, 50)),
            GameLineRefiner.atIconSymbols(line),
        )

        // Leading icon at index 0 returns null so withoutLeadingIcon handles it later.
        val leadingIcon = RawRecognisedLine("★アタッカー", box(20, 20, 160, 50), listOf(elementOf("★アタッカー", 20, 20, 160)))
        assertNull(GameLineRefiner.atIconSymbols(leadingIcon))

        assertTrue(GameLineRefiner.isIcon('①'))
        assertTrue(GameLineRefiner.isIcon('★'))
        assertTrue(GameLineRefiner.isIcon('❤'))
        assertFalse(GameLineRefiner.isIcon('A'))
        assertFalse(GameLineRefiner.isIcon('あ'))
    }

    @Test
    fun `atDividers atInkGaps and atIcons split merged skill labels and trim trailing icon badges`() {
        // atDividers splits pipe-divided labels proportionally and strips stray strokes.
        val divided = GameLine("ノーマルスキル||パッシブスキル", box(40, 20, 360, 56), backdropError = 2.0, typeHeight = 30)
        val divPieces = GameLineRefiner.atDividers(divided)
        assertEquals(listOf("ノーマルスキル", "パッシブスキル"), divPieces.map { it.text })
        assertEquals(box(40, 20, 180, 56), divPieces[0].bounds)
        assertEquals(box(220, 20, 360, 56), divPieces[1].bounds)

        // atInkGaps splits two Japanese labels across an un-inked gap >= typeHeight, even with trailing "+!".
        val inkGapFrame = frameOf(360, 80) { x, y ->
            when {
                y in 28..44 && x in 40 until 140 && (x % 4) < 2 -> grey(240) // first label ink
                y in 28..44 && x in 190 until 290 && (x % 4) < 2 -> grey(240) // second label ink after 50-px gap
                else -> grey(30)
            }
        }
        val mergedTabs = GameLine("ノーマルスキル+ パッシブスキル+!", box(36, 20, 296, 60), backdropError = 2.0, typeHeight = 30)
        val gapSplit = GameLineRefiner.atInkGaps(inkGapFrame, mergedTabs)
        assertEquals(2, gapSplit.size)
        assertEquals(40, gapSplit[0].bounds.left)
        assertEquals(138, gapSplit[0].bounds.right)
        assertEquals(192, gapSplit[1].bounds.left)
        assertEquals(290, gapSplit[1].bounds.right)

        // Latin spaced words are never cut by atInkGaps.
        val latin = GameLine("AUTO MODE", box(36, 20, 296, 60), backdropError = 2.0, typeHeight = 30)
        assertEquals(listOf(latin), GameLineRefiner.atInkGaps(inkGapFrame, latin))

        // atIcons keeps letters before embedded icons up to the least-inked column near estimated width.
        val iconTailFrame = frameOf(260, 80) { x, y ->
            when {
                // "(臨戦)" is 3.1 units of 9.4 total -> estimate = 20 + 65 = 85; un-inked columns at 78..85.
                y in 28..44 && x in 24..76 && (x % 4) < 2 -> grey(240)
                y in 28..44 && x in 86..215 && (x % 4) < 2 -> grey(240)
                else -> grey(30)
            }
        }
        val withTrailingIcons = GameLine("(臨戦)★食★STRIKE", box(20, 20, 220, 60), backdropError = 2.0, typeHeight = 30)
        val trimmedIcon = GameLineRefiner.atIcons(iconTailFrame, withTrailingIcons)
        assertEquals(1, trimmedIcon.size)
        assertEquals("(臨戦)", trimmedIcon[0].text)
        assertEquals(85, trimmedIcon[0].bounds.right)
    }

    @Test
    fun `withoutTail drops phantom bubble-tail marks keeps genuine question marks and crops filled help buttons`() {
        val target = box(40, 20, 200, 60) // height = 40 -> tail = 16 (x in 184 until 200)

        // Phantom '?' from pointed bubble tail: page white runs continuously in from box.right, zero interior ink.
        val phantomFrame = frameOf(240, 90) { x, y ->
            when {
                x in 44..160 && y in 30..44 && (x % 6) < 2 -> grey(240) // white text inside dark bubble
                x >= 194 -> grey(240) // white page encroaching at the right end of box
                else -> grey(40)
            }
        }
        assertEquals("それはよかった" to target, GameLineRefiner.withoutTail(phantomFrame, "それはよかった?", target))

        // Real '？' inside tail (surrounded by bubble panel before box.right, share >= TAIL_MAX_INK = 0.01).
        val realMarkFrame = frameOf(240, 90) { x, y ->
            when {
                x in 44..160 && y in 30..44 && (x % 6) < 2 -> grey(240)
                x in 188..193 && y in 28..48 -> grey(240) // real '？' glyph strokes inside tail, 194..199 still panel
                else -> grey(40)
            }
        }
        assertEquals("バグ？" to target, GameLineRefiner.withoutTail(realMarkFrame, "バグ？", target))

        // Filled help button '?' (>= 50% of tail filled) drops '?' AND shrinks box.right back to word ink end.
        val buttonFrame = frameOf(240, 90) { x, y ->
            when {
                x in 44..149 && y in 30..44 && (x % 5) < 2 -> grey(30) // dark word "生徒" ending at x = 146
                x in 182 until 200 && y in 22 until 58 -> grey(30) // dark filled square button in tail (90% filled)
                else -> grey(240) // light panel
            }
        }
        val (buttonText, buttonBox) = GameLineRefiner.withoutTail(buttonFrame, "生徒?", target)
        assertEquals("生徒", buttonText)
        assertEquals(box(40, 20, 147, 60), buttonBox)
    }

    @Test
    fun `withBracketedParts and withWordPiecesJoined merge split variants and broken words without merging separate labels`() {
        val name = GameLine("ハナコ", box(40, 20, 130, 56), backdropError = 2.0, typeHeight = 32)
        val variant = GameLine("(水着)", box(142, 18, 220, 58), backdropError = 4.0, typeHeight = 38)
        val joinedVariant = GameLineRefiner.withBracketedParts(listOf(name, variant))
        assertEquals(1, joinedVariant.size)
        assertEquals("ハナコ(水着)", joinedVariant[0].text)
        assertEquals(box(40, 18, 220, 58), joinedVariant[0].bounds)
        assertEquals(4.0, joinedVariant[0].backdropError)
        // Word's own typeHeight is preserved rather than the taller brackets'.
        assertEquals(32, joinedVariant[0].typeHeight)

        // Broken katakana tab word (gap 22 <= 61 * 0.4 = 24.4) joins; separate labels 40 px apart stay apart.
        val halfA = GameLine("エピソー", box(40, 20, 220, 81), backdropError = 2.0, typeHeight = 61)
        val halfB = GameLine("ド情報", box(242, 20, 380, 81), backdropError = 3.0, typeHeight = 61)
        val separateLabel = GameLine("ストーリー", box(420, 20, 620, 81), backdropError = 2.0, typeHeight = 61)
        val joinedWords = GameLineRefiner.withWordPiecesJoined(listOf(halfA, halfB, separateLabel))
        assertEquals(2, joinedWords.size)
        assertEquals("エピソード情報", joinedWords[0].text)
        assertEquals(box(40, 20, 380, 81), joinedWords[0].bounds)
        assertEquals("ストーリー", joinedWords[1].text)
    }
}
