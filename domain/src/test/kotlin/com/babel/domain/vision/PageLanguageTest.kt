package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The strings are what manga-ocr actually returned for these pages, mangling
 * and all. Tidy Japanese and tidy Chinese would not exercise the thing this
 * has to survive: the Japanese recogniser hallucinating a kana into Chinese
 * artwork.
 */
class PageLanguageTest {

    /** Real balloons off `jap-mag-10`. */
    private val japanese = listOf(
        "さっき消滅してなかった!?",
        "ああしたとも",
        "今の私は霊基トランクから召喚された器だ",
    )

    /** Real balloons off `cn-mag-01`, including the one that came back with a kana. */
    private val chinese = listOf(
        "老怖汶是要人贅勘解由小路家!嶋大樺了!♡",
        "什、什幺呵......上来就。",
        "明明和怖父的青春活み進恋愛都没送行到。",
        "那......等、等我先去做一下新娘修行!",
    )

    @Test
    fun `one Japanese balloon settles the page`() {
        val page = PageLanguage()
        page.observe(japanese.first())
        assertEquals(PageLanguage.Verdict.JAPANESE, page.verdict)
    }

    @Test
    fun `a Chinese page is not called Japanese by a hallucinated kana`() {
        val page = PageLanguage()
        chinese.forEach(page::observe)
        assertEquals(PageLanguage.Verdict.NOT_JAPANESE, page.verdict)
    }

    /**
     * The balloon this whole mechanism exists for. On its own it decides
     * nothing — which is the point, and why it has to wait for the page.
     */
    @Test
    fun `a Han-only balloon leaves the page undecided`() {
        val page = PageLanguage()
        page.observe("先生")
        assertEquals(PageLanguage.Verdict.UNDECIDED, page.verdict)
    }

    @Test
    fun `a Japanese page opening with a Han-only balloon still comes out Japanese`() {
        val page = PageLanguage()
        page.observe("先生")
        page.observe("大丈夫")
        assertEquals(PageLanguage.Verdict.UNDECIDED, page.verdict)
        page.observe(japanese[2])
        assertEquals(PageLanguage.Verdict.JAPANESE, page.verdict)
    }

    /** Three short balloons are not enough Han to call it. */
    @Test
    fun `a few short Han balloons do not settle it`() {
        val page = PageLanguage()
        listOf("先生", "大丈夫", "本当").forEach(page::observe)
        assertEquals(PageLanguage.Verdict.UNDECIDED, page.verdict)
    }

    @Test
    fun `reset starts the next page clean`() {
        val page = PageLanguage()
        page.observe(japanese.first())
        page.reset()
        assertEquals(PageLanguage.Verdict.UNDECIDED, page.verdict)
    }

    /**
     * Hangul settles a page the way kana does, and takes precedence: a Korean
     * page read by a Japanese engine comes back full of invented kana
     * (measured on `kr-mag-01`), so a page holding both is Korean.
     */
    @Test
    fun `one Hangul balloon settles the page as Korean`() {
        val page = PageLanguage()
        page.observe("이부키, 뭐해?")
        assertEquals(PageLanguage.Verdict.KOREAN, page.verdict)
    }

    @Test
    fun `Hangul wins over invented kana on the same page`() {
        val page = PageLanguage()
        page.observe("olデヲル号おH?")
        assertEquals(PageLanguage.Verdict.JAPANESE, page.verdict)
        page.observe("이부키, 뭐해?")
        assertEquals(PageLanguage.Verdict.KOREAN, page.verdict)
    }

    @Test
    fun `a Japanese page is never called Korean`() {
        val page = PageLanguage()
        japanese.forEach(page::observe)
        assertEquals(PageLanguage.Verdict.JAPANESE, page.verdict)
    }
}
