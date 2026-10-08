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

        val moved = lines.next(listOf(line("風紀委員会", left = 674)), now = 666)
        assertEquals(listOf(shown), moved.gone)
        assertEquals(674, lines.next(listOf(line("風紀委員会", left = 674)), now = 1000).settled.single().line.bounds.left)
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
