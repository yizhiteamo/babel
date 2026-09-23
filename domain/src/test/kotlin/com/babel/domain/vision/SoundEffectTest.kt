package com.babel.domain.vision

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two sound effects and the seven real lines this rule was measured on.
 *
 * The strings are the ones `DetectorThresholdMeasurementTest` found in the
 * `text_free` class across eight pages of `comic-sample`, so a change that
 * breaks the measured split fails here rather than on a device
 * (`docs/milestones/v2.md`).
 */
class SoundEffectTest {

    @Test
    fun `the measured sound effects are dropped`() {
        assertTrue(SoundEffect.isDrawnNoise("ガチャ"))
        assertTrue(SoundEffect.isDrawnNoise("ガチャガチャ"))
    }

    @Test
    fun `the measured free text is kept`() {
        val kept = listOf(
            "私はたった今から",
            "データを捨てる！",
            "私は所詮しがない",
            "データによるデータの…（暴走）",
            "聖女見習いちゃん",
            "ケンチり野郎さ",
            "フルーツタルトが大好物",
        )
        for (text in kept) {
            assertFalse(SoundEffect.isDrawnNoise(text), text)
        }
    }

    @Test
    fun `punctuation does not smuggle a noise through`() {
        assertTrue(SoundEffect.isDrawnNoise("ガチャッ！"))
        assertTrue(SoundEffect.isDrawnNoise("ドドド…"))
        assertTrue(SoundEffect.isDrawnNoise("バーン！！"))
    }

    @Test
    fun `hiragana is speech even when it is short`() {
        // Sound effects are written in hiragana too, and this rule lets those
        // through on purpose: losing `はい` costs more than keeping a `どどど`.
        assertFalse(SoundEffect.isDrawnNoise("はい"))
        assertFalse(SoundEffect.isDrawnNoise("うん"))
        assertFalse(SoundEffect.isDrawnNoise("そう"))
    }

    @Test
    fun `a long katakana line is speech`() {
        // The length bound exists for exactly this: a loanword-heavy line is
        // katakana all through and is still somebody talking.
        assertFalse(SoundEffect.isDrawnNoise("コンピューターウイルスバスター"))
    }

    @Test
    fun `an empty read is not a sound effect`() {
        // A failed recognition, which the caller drops for its own reasons.
        assertFalse(SoundEffect.isDrawnNoise(""))
        assertFalse(SoundEffect.isDrawnNoise("…！"))
    }
}
