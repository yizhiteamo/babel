package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PatternReadingTest {

    @Test
    fun `a border read as one kana over and over is a pattern`() {
        assertTrue(PatternReading.isPattern("ああああああああああああああああああああああああ"))
        assertTrue(PatternReading.isPattern("あいあいあいあいあいあいあいあい"))
    }

    @Test
    fun `a shout is speech`() {
        assertFalse(PatternReading.isPattern("ああああ!!"))
        assertFalse(PatternReading.isPattern("ミュイミュイ"))
    }

    @Test
    fun `a sentence is speech`() {
        assertFalse(PatternReading.isPattern("ムギとマロンに織姫と彦星の格好を…"))
    }
}
