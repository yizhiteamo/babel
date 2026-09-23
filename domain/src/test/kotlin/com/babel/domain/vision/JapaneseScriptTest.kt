package com.babel.domain.vision

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class JapaneseScriptTest {

    @Test
    fun `hiragana is japanese`() {
        assertTrue(JapaneseScript.isPresentIn("いいの?"))
    }

    @Test
    fun `katakana is japanese`() {
        assertTrue(JapaneseScript.isPresentIn("スーパーアルバイター"))
    }

    @Test
    fun `mixed kana and kanji is japanese`() {
        assertTrue(JapaneseScript.isPresentIn("先生も汗拭きシート使いますか?"))
    }

    /**
     * The case that provoked this. ML Kit's Japanese recogniser reads Latin, so
     * in manga mode an English page was stamped `ja` and translated ja→zh.
     */
    @Test
    fun `latin text is not japanese`() {
        assertFalse(JapaneseScript.isPresentIn("The quick brown fox"))
    }

    /** Shared with Chinese, so it is left to detection rather than claimed. */
    @Test
    fun `han without kana is not claimed`() {
        assertFalse(JapaneseScript.isPresentIn("今天天气很好"))
    }

    @Test
    fun `punctuation alone is not japanese`() {
        assertFalse(JapaneseScript.isPresentIn("......!?"))
    }

    @Test
    fun `empty text is not japanese`() {
        assertFalse(JapaneseScript.isPresentIn(""))
    }

    /**
     * The three real strings manga-ocr produced from `jap-mag-08`, an English
     * page. It does not fail on a language it was not built for — it invents
     * one — and those inventions were translated and drawn over the English
     * (`docs/milestones/v2.md`). Recognition falls back to ML Kit when this
     * says no, so these are what decides it.
     */
    @Test
    fun `manga-ocr output that is not japanese script at all`() {
        val invented = listOf(
            "Andoote:goingobeaGeatWittiParentyAstrerdpswinosantrigadarontosarigin",
            "WindrisntthatSamantha2Thebig.hatThettbookstingoksheshestiooksHicabrdAw",
            "Shame,relly,Shes.gotacuxelittterfiaceontLertingrolddyinapleOfbooks.",
        )
        for (text in invented) {
            assertFalse(JapaneseScript.couldBeJapanese(text), text)
        }
    }

    @Test
    fun `anything written in kana or han could be japanese`() {
        assertTrue(JapaneseScript.couldBeJapanese("わたしの"))
        assertTrue(JapaneseScript.couldBeJapanese("データは集まった"))
        assertTrue(JapaneseScript.couldBeJapanese("先生も汗拭きシート使いますか?"))
        // Han alone is ambiguous as a *language* claim and is not one here: the
        // question is only whether a Japanese recogniser produced Japanese
        // script, and 懺悔室 plainly is.
        assertTrue(JapaneseScript.couldBeJapanese("懺悔室"))
    }

    @Test
    fun `nothing but punctuation is not japanese script`() {
        // A balloon read as dots is a failed read, and falling back to ML Kit
        // is the right answer for it too.
        assertFalse(JapaneseScript.couldBeJapanese("......!?"))
        assertFalse(JapaneseScript.couldBeJapanese(""))
    }
}
