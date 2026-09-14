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
}
