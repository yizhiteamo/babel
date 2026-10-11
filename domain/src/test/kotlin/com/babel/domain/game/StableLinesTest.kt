package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StableLinesTest {

    private fun line(text: String, left: Int = 400, top: Int = 1100, width: Int = 40 * text.length) =
        GameLine(text, TextBounds(left, top, left + width, top + 56, CoordinateSpace.SCREEN), backdropError = 3.0)

    @Test
    fun `a line is published once two frames agree`() {
        val lines = StableLines()

        assertTrue(lines.next(listOf(line("ふふ。みなさん")), now = 0).settled.isEmpty())
        val second = lines.next(listOf(line("ふふ。みなさん")), now = 333)
        assertEquals(1, second.settled.size)
        assertEquals(333, second.settled.single().settledAfterMs)
        assertTrue(lines.next(listOf(line("ふふ。みなさん")), now = 666).settled.isEmpty())
    }

    /** A typewriter line is not translated half-written, and the wait counts from its first letters. */
    @Test
    fun `a growing line waits until it stops growing`() {
        val lines = StableLines()

        lines.next(listOf(line("ふふ")), now = 0)
        assertTrue(lines.next(listOf(line("ふふ。みな")), now = 333).settled.isEmpty())
        assertTrue(lines.next(listOf(line("ふふ。みなさん、ありがとう")), now = 666).settled.isEmpty())
        val done = lines.next(listOf(line("ふふ。みなさん、ありがとう")), now = 1000)

        assertEquals("ふふ。みなさん、ありがとう", done.settled.single().line.text)
        assertEquals(1000, done.settled.single().settledAfterMs)
    }

    @Test
    fun `the next line in the same box withdraws the last one`() {
        val lines = StableLines()
        lines.next(listOf(line("一行目")), now = 0)
        val shown = lines.next(listOf(line("一行目")), now = 333).settled.single().key

        val replaced = lines.next(listOf(line("二行目です")), now = 666)

        assertEquals(listOf(shown), replaced.gone)
    }

    /** One frame where the recogniser missed it must not make the translation flicker. */
    @Test
    fun `a line missing for one frame stays`() {
        val lines = StableLines()
        lines.next(listOf(line("選択肢")), now = 0)
        lines.next(listOf(line("選択肢")), now = 333)

        assertTrue(lines.next(emptyList(), now = 666).gone.isEmpty())
        val back = lines.next(listOf(line("選択肢")), now = 1000)
        assertTrue(back.gone.isEmpty())
        assertTrue(back.settled.isEmpty(), "already shown, not shown again")
    }

    @Test
    fun `a line gone for four frames is withdrawn`() {
        val lines = StableLines()
        lines.next(listOf(line("選択肢")), now = 0)
        val key = lines.next(listOf(line("選択肢")), now = 333).settled.single().key

        repeat(3) { assertTrue(lines.next(emptyList(), now = 666L + it * 333).gone.isEmpty()) }
        assertEquals(listOf(key), lines.next(emptyList(), now = 1700).gone)
    }

    @Test
    fun `a line gone when the scene changes is withdrawn at once`() {
        val lines = StableLines()
        lines.next(listOf(line("選択肢")), now = 0)
        val key = lines.next(listOf(line("選択肢")), now = 333).settled.single().key

        assertEquals(listOf(key), lines.next(emptyList(), now = 666, sceneChanged = true).gone)
    }

    @Test
    fun `a line sliding into place settles where it stops`() {
        val lines = StableLines()
        lines.next(listOf(line("アコ", top = 1060)), now = 0)
        assertTrue(lines.next(listOf(line("アコ", top = 1080)), now = 333).settled.isEmpty())
        val stopped = lines.next(listOf(line("アコ", top = 1100)), now = 666)
        assertTrue(stopped.settled.isEmpty())

        assertEquals(1100, lines.next(listOf(line("アコ", top = 1100)), now = 1000).settled.single().line.bounds.top)
    }

    @Test
    fun `a line whose place went blank is withdrawn at once`() {
        val lines = StableLines()
        lines.next(listOf(line("選択肢")), now = 0)
        val key = lines.next(listOf(line("選択肢")), now = 333).settled.single().key

        assertEquals(listOf(key), lines.next(emptyList(), now = 666, stillInked = { false }).gone)
    }

    /** The recogniser's box moves by a few pixels between frames; that is the same place. */
    @Test
    fun `a box that wobbles is the same line`() {
        val lines = StableLines()
        lines.next(listOf(line("こんにちは", left = 400, top = 1100)), now = 0)

        assertEquals(1, lines.next(listOf(line("こんにちは", left = 406, top = 1103)), now = 333).settled.size)
    }

    /** Measured on moving art: one still line read four slightly different ways. */
    @Test
    fun `a line read slightly differently stays published`() {
        val lines = StableLines()
        lines.next(listOf(line("……そういえば、先生。クリスマスは何するの?")), now = 0)
        val shown = lines.next(listOf(line("……そういえば、先生。クリスマスは何するの?")), now = 333)
        assertEquals(1, shown.settled.size)

        val jitter = lines.next(listOf(line("……そういえば、先生。クリスマスは何するの？")), now = 666)
        assertTrue(jitter.gone.isEmpty())
        assertTrue(jitter.settled.isEmpty())
    }

    /** Measured: the affiliation flipped between two readings one glyph apart, and flickered. */
    @Test
    fun `a short label misread by one character stays published`() {
        val lines = StableLines()
        lines.next(listOf(line("風紀委員会")), now = 0)
        lines.next(listOf(line("風紀委員会")), now = 333)

        val variant = lines.next(listOf(line("風紀委貝会")), now = 666)
        assertTrue(variant.gone.isEmpty())
        assertTrue(variant.settled.isEmpty())
    }

    @Test
    fun `an eight letter label misread in two places stays published`() {
        val lines = StableLines()
        lines.next(listOf(line("訓練用ゴーレム")), now = 0)
        lines.next(listOf(line("訓練用ゴーレム")), now = 333)

        assertTrue(lines.next(listOf(line("、訓練用ゴーレ厶")), now = 666).gone.isEmpty())
    }

    @Test
    fun `a two letter name one character off is a new name`() {
        val lines = StableLines()
        lines.next(listOf(line("アコ")), now = 0)
        lines.next(listOf(line("アコ")), now = 333)

        assertEquals(1, lines.next(listOf(line("ミコ")), now = 666).gone.size)
    }

    @Test
    fun `a label whose dots count up stays published`() {
        val lines = StableLines()
        lines.next(listOf(line("接続中.")), now = 0)
        lines.next(listOf(line("接続中.")), now = 333)

        val more = lines.next(listOf(line("接続中...")), now = 666)
        assertTrue(more.gone.isEmpty())
        assertTrue(more.settled.isEmpty())
    }

    @Test
    fun `an entry read without its first line stays published`() {
        val lines = StableLines()
        val whole = "「プログラム　スタート。霊子変換を開始します。」"
        lines.next(listOf(line(whole)), now = 0)
        lines.next(listOf(line(whole)), now = 333)

        assertTrue(lines.next(listOf(line("霊子変換を開始します。」")), now = 666).gone.isEmpty())
    }

    @Test
    fun `a closing mark read on one frame and not the next is the same line`() {
        val lines = StableLines()
        lines.next(listOf(line("「…………あの……………せん、ぱい」")), now = 0)
        lines.next(listOf(line("「…………あの……………せん、ぱい」")), now = 333)

        assertTrue(lines.next(listOf(line("「…………あの……………せん、ぱい」！")), now = 666).gone.isEmpty())
    }

    @Test
    fun `half a line with a mark misread is a miss`() {
        val lines = StableLines()
        lines.next(listOf(line("「……あの……せん、ぱい」")), now = 0)
        lines.next(listOf(line("「……あの……せん、ぱい」")), now = 333)

        assertTrue(lines.next(listOf(line("「……あ·")), now = 666).gone.isEmpty())
    }

    @Test
    fun `a few characters of a still line with one misread is a miss`() {
        val lines = StableLines()
        lines.next(listOf(line("「…あの…せん、ぱい」")), now = 0)
        lines.next(listOf(line("「…あの…せん、ぱい」")), now = 333)

        assertTrue(lines.next(listOf(line("「あの·")), now = 666).gone.isEmpty())
    }

    @Test
    fun `a speaker's name seen before is published at once`() {
        val lines = StableLines()
        lines.next(listOf(line("マシュ")), now = 0)
        lines.next(listOf(line("マシュ")), now = 333)
        lines.next(listOf(line("ロマン", left = 400)), now = 666)
        lines.next(listOf(line("ロマン", left = 400)), now = 1000)

        assertEquals("マシュ", lines.next(listOf(line("マシュ")), now = 1333).settled.single().line.text)
    }

    @Test
    fun `a line of dialogue seen before still waits for a second frame`() {
        val lines = StableLines()
        lines.next(listOf(line("はい。")), now = 0)
        lines.next(listOf(line("はい。")), now = 333)
        lines.next(listOf(line("いいえ、ちがいます。")), now = 666)

        assertTrue(lines.next(listOf(line("はい。")), now = 1000).settled.isEmpty())
    }

    @Test
    fun `a place read two ways by turns stops flickering`() {
        val lines = StableLines()
        val a = line("貴方たちはわたしの道具だって言ったでしょ。")
        val b = line("……ダメ？司令官が最前線に出るワケないじゃない")
        lines.next(listOf(a), now = 0)
        lines.next(listOf(a), now = 333)
        lines.next(listOf(b), now = 666)
        lines.next(listOf(a), now = 1000)
        lines.next(listOf(a), now = 1333)

        assertTrue(lines.next(listOf(b), now = 1666).gone.isEmpty())
    }

    @Test
    fun `a long line seen before is published at once when scrolled back`() {
        val lines = StableLines()
        val long = "ゼロではない。いいえ、ゼロにはならない。"
        lines.next(listOf(line(long)), now = 0)
        lines.next(listOf(line(long)), now = 333)
        lines.next(emptyList(), now = 666, sceneChanged = true)

        assertEquals(1, lines.next(listOf(line(long, top = 500)), now = 1000).settled.size)
    }

    @Test
    fun `a name read one character off is the known name`() {
        val lines = StableLines()
        lines.next(listOf(line("チナツ")), now = 0)
        lines.next(listOf(line("チナツ")), now = 333)
        lines.next(listOf(line("ヒナ", left = 900)), now = 666)

        assertEquals("チナツ", lines.next(listOf(line("チナッ", left = 1500)), now = 1000).settled.single().line.text)
    }

    /** The next line, read off-by-one-word noisily, is still a new line. */
    @Test
    fun `a different line in the same box is still withdrawn`() {
        val lines = StableLines()
        lines.next(listOf(line("……袋は大丈夫よ、ありがとう。")), now = 0)
        lines.next(listOf(line("……袋は大丈夫よ、ありがとう。")), now = 333)

        assertEquals(1, lines.next(listOf(line("え、ケーキ食べないの?うっそ")), now = 666).gone.size)
    }

    /** Measured: after a longer name the affiliation's translation stayed where it was, over the name. */
    @Test
    fun `the same words moved along are drawn again where they are`() {
        val lines = StableLines()
        lines.next(listOf(line("風紀委員会", left = 589)), now = 0)
        val shown = lines.next(listOf(line("風紀委員会", left = 589)), now = 333).settled.single().key

        // Known words: drawn again where they are at once.
        val moved = lines.next(listOf(line("風紀委員会", left = 674)), now = 666)
        assertEquals(listOf(shown), moved.gone)
        assertEquals(674, moved.settled.single().line.bounds.left)
    }

    /** The recogniser's box for one still line takes in more on one side some frames. */
    @Test
    fun `a box that stretches on one side has not moved`() {
        val lines = StableLines()
        lines.next(listOf(line("これは大事なところね", left = 434, width = 824)), now = 0)
        lines.next(listOf(line("これは大事なところね", left = 434, width = 824)), now = 333)

        val stretched = lines.next(listOf(line("これは大事なところね", left = 403, width = 881)), now = 666)
        assertTrue(stretched.gone.isEmpty())
        assertTrue(stretched.settled.isEmpty())
    }

    /** One frame read only the first line of a wrapped sentence. */
    @Test
    fun `a sentence read without its end stays published`() {
        val lines = StableLines()
        val whole = "ありがとうございます。どれどれ……仁志川センターを経由したところ……と。"
        lines.next(listOf(line(whole)), now = 0)
        lines.next(listOf(line(whole)), now = 333)

        val cut = lines.next(listOf(line("ありがとうございます。どれどれ……仁志川センター")), now = 666)
        assertTrue(cut.gone.isEmpty())
        val back = lines.next(listOf(line(whole)), now = 1000)
        assertTrue(back.gone.isEmpty())
        assertTrue(back.settled.isEmpty())
    }

    @Test
    fun `reset hands back everything shown`() {
        val lines = StableLines()
        lines.next(listOf(line("あいう"), line("かきく", top = 1300)), now = 0)
        lines.next(listOf(line("あいう"), line("かきく", top = 1300)), now = 333)

        assertEquals(2, lines.reset().size)
    }
}
