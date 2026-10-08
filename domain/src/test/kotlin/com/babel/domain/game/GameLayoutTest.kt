package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import com.babel.domain.game.GameLayout.InkRun
import kotlin.test.Test
import kotlin.test.assertEquals

/** Boxes and colours are the ones measured on Blue Archive screenshots. */
class GameLayoutTest {

    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, error: Double = 5.0) =
        GameLine(text, TextBounds(l, t, r, b, CoordinateSpace.SCREEN), error)

    @Test
    fun `a white name and a cyan affiliation are split in their gap`() {
        val runs = listOf(
            InkRun(431, 498, 228, 232, 235),
            InkRun(632, 666, 225, 230, 233),
            InkRun(700, 745, 126, 197, 236),
            InkRun(756, 804, 128, 197, 238),
        )

        assertEquals(listOf(683), GameLayout.splitPoints(runs, lineHeight = 80))
    }

    /** A thin glyph's run reads lighter than its word; it is not a second piece. */
    @Test
    fun `a pale thin glyph inside one word does not split it`() {
        val runs = listOf(
            InkRun(700, 745, 126, 197, 236, ink = 900),
            InkRun(756, 804, 128, 197, 238, ink = 900),
            InkRun(831, 845, 190, 210, 225, ink = 60),
            InkRun(870, 920, 126, 196, 233, ink = 900),
        )

        assertEquals(emptyList(), GameLayout.splitPoints(runs, lineHeight = 80))
    }

    @Test
    fun `one colour is not split`() {
        val runs = listOf(InkRun(426, 503, 240, 240, 241), InkRun(590, 665, 241, 242, 242))

        assertEquals(emptyList(), GameLayout.splitPoints(runs, lineHeight = 94))
    }

    @Test
    fun `a wrapped line of dialogue is one utterance`() {
        val first = line("う、ウソだろ?", 435, 1122, 837, 1178)
        val second = line("こんなすごい演奏を、女子高生が…!?", 414, 1208, 1558, 1275)

        val joined = GameLayout.joinWrapped(listOf(first, second))

        assertEquals(1, joined.size)
        assertEquals("う、ウソだろ?こんなすごい演奏を、女子高生が…!?", joined.single().text)
        assertEquals(TextBounds(414, 1122, 1558, 1275, CoordinateSpace.SCREEN), joined.single().bounds)
    }

    @Test
    fun `a speaker above the dialogue is not joined to it`() {
        val speaker = line("通行人A", 375, 970, 755, 1064)
        val dialogue = line("う、ウソだろ?", 435, 1122, 837, 1178)

        assertEquals(2, GameLayout.joinWrapped(listOf(speaker, dialogue)).size)
    }

    @Test
    fun `two choices a button apart are not joined`() {
        val a = line("カッコイイ。", 1340, 468, 1824, 524)
        val b = line("どこかで聴いたことがあるような…。", 1027, 665, 2139, 725)

        assertEquals(2, GameLayout.joinWrapped(listOf(a, b)).size)
    }

    @Test
    fun `a speaker label just over the backdrop threshold is kept above dialogue`() {
        val speaker = line("通行人A", 375, 970, 755, 1064, error = 15.0)
        val dialogue = line("う、ウソだろ?", 435, 1122, 837, 1178, error = 6.6)

        assertEquals(listOf(speaker, dialogue), GameLayout.select(listOf(speaker, dialogue), frameHeight = 1440))
    }

    @Test
    fun `lettering on art with nothing translated below stays out`() {
        val art = line("イペントアイテムを種得しましょう!", 1717, 954, 2525, 1017, error = 20.0)

        assertEquals(emptyList(), GameLayout.select(listOf(art), frameHeight = 1440))
    }
}
