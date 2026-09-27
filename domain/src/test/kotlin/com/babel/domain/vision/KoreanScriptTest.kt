package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KoreanScriptTest {

    /** Real balloons off `kr-mag-01`. */
    @Test
    fun `Hangul syllables are Korean`() {
        assertTrue(KoreanScript.isPresentIn("이부키, 뭐해?"))
        assertTrue(KoreanScript.isPresentIn("아이스크림 가게를 사주고 싶어"))
        assertTrue(KoreanScript.isPresentIn("헉"))
    }

    /**
     * What a Japanese engine made of that same artwork. None of it is Hangul,
     * which is exactly why it fooled every test this project had.
     */
    @Test
    fun `invented Japanese is not Korean`() {
        assertFalse(KoreanScript.isPresentIn("olデヲル号おH?"))
        assertFalse(KoreanScript.isPresentIn("それを考えなければ、今、2018年3月19日"))
    }

    @Test
    fun `other scripts are not Korean`() {
        assertFalse(KoreanScript.isPresentIn("さっき消滅してなかった!?"))
        assertFalse(KoreanScript.isPresentIn("老师这是要入赘吗?"))
        assertFalse(KoreanScript.isPresentIn("The quick brown fox"))
        assertFalse(KoreanScript.isPresentIn(""))
    }
}
