package com.babel.domain.translation

import kotlin.test.Test
import kotlin.test.assertEquals

class NameMarksTest {

    private val names = mapOf("ミナ" to "米娜", "アコ" to "阿科", "ヒナ" to "日奈")

    @Test
    fun `names in a line become marks`() {
        val marked = NameMarks.mark("ミナとアコも、連絡ありがとう！", NameMarks.namesIn("ミナとアコも、連絡ありがとう！", names))

        assertEquals("{N2}と{N1}も、連絡ありがとう！".length, marked.text.length)
        assertEquals(false, "ミナ" in marked.text || "アコ" in marked.text)
    }

    @Test
    fun `a name inside a longer word is left alone`() {
        assertEquals(emptyMap(), NameMarks.namesIn("ヒナタさん、こんにちは。", names))
    }
}
