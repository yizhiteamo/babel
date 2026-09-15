package com.babel.domain.vision

import kotlin.test.assertEquals
import org.junit.Test

class OcrPunctuationTest {

    /** The measured case: the content vanishes and only the dots survive. */
    @Test
    fun `a run of dots becomes one ellipsis`() {
        assertEquals("…あ", OcrPunctuation.normalize("......あ"))
    }

    @Test
    fun `runs are collapsed wherever they appear`() {
        assertEquals(
            "…仕事中、突然視界が高くなったり…増えたり…手足…色…声が変化して…",
            OcrPunctuation.normalize(
                "...仕事中、突然視界が高くなったり...増えたり...手足..色...声が変化して...",
            ),
        )
    }

    @Test
    fun `middle dots and half width forms count too`() {
        assertEquals("あ…い", OcrPunctuation.normalize("あ・・・い"))
        assertEquals("あ…い", OcrPunctuation.normalize("あ‥い"))
    }

    /**
     * `。` and `、` are sentence punctuation, not ellipsis fragments. Collapsing
     * them would change what the sentence says.
     */
    @Test
    fun `a single full stop survives`() {
        assertEquals("終わり。", OcrPunctuation.normalize("終わり。"))
        assertEquals("周囲が泣いたり、騒いだり", OcrPunctuation.normalize("周囲が泣いたり、騒いだり"))
    }

    /** A colon ending a Japanese line is a misread ellipsis, not a colon. */
    @Test
    fun `a trailing colon becomes an ellipsis`() {
        assertEquals("そっちは私の使用済み…", OcrPunctuation.normalize("そっちは私の使用済み:"))
    }

    @Test
    fun `a colon inside a line is left alone`() {
        assertEquals("10:30に", OcrPunctuation.normalize("10:30に"))
    }

    @Test
    fun `question and exclamation marks are untouched`() {
        assertEquals("がんばりまーす!!", OcrPunctuation.normalize("がんばりまーす!!"))
        assertEquals("いいの?", OcrPunctuation.normalize("いいの?"))
    }

    @Test
    fun `splitting puts the sentence on its own`() {
        val affixed = OcrPunctuation.split("…先生?")

        assertEquals("…", affixed.prefix)
        assertEquals("先生?", affixed.core)
        assertEquals("", affixed.suffix)
    }

    @Test
    fun `splitting takes punctuation off both ends`() {
        val affixed = OcrPunctuation.split("…目を閉じて…")

        assertEquals("…", affixed.prefix)
        assertEquals("目を閉じて", affixed.core)
        assertEquals("…", affixed.suffix)
    }

    @Test
    fun `reattaching restores what was taken`() {
        val affixed = OcrPunctuation.split("…あ")

        assertEquals("…啊", affixed.reattach("啊"))
    }

    /** Punctuation only: there is no sentence to protect, so nothing is split. */
    @Test
    fun `text that is all punctuation is left whole`() {
        val affixed = OcrPunctuation.split("……")

        assertEquals("", affixed.prefix)
        assertEquals("……", affixed.core)
        assertEquals("", affixed.suffix)
    }

    @Test
    fun `a line is cut at its ellipses`() {
        assertEquals(
            listOf("目を", "閉じて", ""),
            OcrPunctuation.fragments(OcrPunctuation.normalize("目を...閉じて...")),
        )
    }

    /** The empty leading fragment is what puts the ellipsis back at the front. */
    @Test
    fun `a leading ellipsis leaves an empty first fragment`() {
        assertEquals(
            listOf("", "あ"),
            OcrPunctuation.fragments(OcrPunctuation.normalize("......あ")),
        )
    }

    @Test
    fun `rejoining restores the punctuation exactly`() {
        val text = "...絶対に...動かないこと..."

        assertEquals(
            OcrPunctuation.normalize(text),
            OcrPunctuation.rejoin(OcrPunctuation.fragments(OcrPunctuation.normalize(text))),
        )
    }

    @Test
    fun `a line without ellipses is one fragment`() {
        assertEquals(
            listOf("いくらでも使ってください"),
            OcrPunctuation.fragments("いくらでも使ってください"),
        )
    }

    /**
     * Splitting leaves unrepaired text alone, so an accessibility label that
     * really does end in a colon is not turned into an ellipsis by the
     * translation layer.
     */
    @Test
    fun `splitting does not repair`() {
        assertEquals(listOf("Name:"), OcrPunctuation.fragments("Name:"))
        assertEquals(listOf("あ......い"), OcrPunctuation.fragments("あ......い"))
    }
}
