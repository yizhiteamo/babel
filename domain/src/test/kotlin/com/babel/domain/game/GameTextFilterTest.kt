package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import com.babel.domain.game.GameTextFilter.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cases are lines measured on real screenshots (`docs/milestones/v3.md`, spike 2). */
class GameTextFilterTest {

    private fun judge(text: String, error: Double, height: Int = 56) =
        GameTextFilter.judge(
            GameLine(text, TextBounds(0, 0, 600, height, CoordinateSpace.SCREEN), error),
            frameHeight = 1440,
        )

    @Test
    fun `dialogue, choices and pop-up text are translated`() {
        assertEquals(Verdict.TRANSLATE, judge("ふふ。みなさん、ありがとうございます。", 6.8))
        assertEquals(Verdict.TRANSLATE, judge("\"どこかで聴いたことがあるような…。", 0.9))
        assertEquals(Verdict.TRANSLATE, judge("ホットチョコ、ツムギの楽譜、レナの巻きを獲得しましょう!", 0.2))
        assertEquals(Verdict.TRANSLATE, judge("お知らせ", 3.5))
    }

    @Test
    fun `counters are left alone`() {
        assertEquals(Verdict.NUMBER, judge("155/240", 3.5))
        assertEquals(Verdict.NUMBER, judge("264,410,355", 2.5))
        assertEquals(Verdict.NUMBER, judge("17:13", 3.6))
        assertEquals(Verdict.NUMBER, judge("1/8", 2.1))
        assertEquals(Verdict.NUMBER, judge("Lv.90", 7.0))
    }

    /** Interface words in Latin are words; an English game is made of them. */
    @Test
    fun `short Latin labels are translated`() {
        assertEquals(Verdict.TRANSLATE, judge("AUTO", 2.4))
        assertEquals(Verdict.TRANSLATE, judge("MENU", 0.4))
    }

    @Test
    fun `lettering on artwork is left alone`() {
        assertEquals(Verdict.ON_ARTWORK, judge("イペントアイテムを種得しましょう!", 20.0))
        assertEquals(Verdict.ON_ARTWORK, judge("オカルト研究会!", 68.1))
        assertEquals(Verdict.ON_ARTWORK, judge("セレクト", 109.5))
    }

    @Test
    fun `logo-sized lettering is left alone`() {
        assertEquals(Verdict.TOO_LARGE, judge("てオカルト研究会!!", 59.1, height = 414))
        assertEquals(Verdict.TOO_LARGE, judge("~学院の不思議と古の呪文~", 3.0, height = 116))
    }

    @Test
    fun `one letter is noise`() {
        assertEquals(Verdict.TOO_SHORT, judge("く", 3.8))
    }
}
