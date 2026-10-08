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

    /** Measured: 「ね!?」 alone is too short to translate, and was dropped from its sentence. */
    @Test
    fun `a short wrap is kept with its sentence`() {
        val first = line("ん?待ってください。どのみち、こちらに請求が回ってくるのは変わりないんですよ", 400, 1121, 2728, 1178)
        val second = line("ね!?", 433, 1213, 560, 1268)

        val kept = GameLayout.joinWrapped(GameLayout.select(listOf(first, second), frameHeight = 1440, frameWidth = 3200))

        assertEquals(1, kept.size)
        assertEquals(1268, kept.single().bounds.bottom)
    }

    @Test
    fun `a speaker above the dialogue is not joined to it`() {
        val speaker = line("通行人A", 375, 970, 755, 1064)
        val dialogue = line("う、ウソだろ?", 435, 1122, 837, 1178)

        assertEquals(2, GameLayout.joinWrapped(listOf(speaker, dialogue)).size)
    }

    /** Ruby over the dialogue made its box as tall as the speaker's; measured, the two were joined. */
    @Test
    fun `a speaker above dialogue with ruby is not joined to it`() {
        val speaker = line("アコ", 426, 980, 595, 1056)
        val dialogue = line("それが、万魔殿が……", 409, 1115, 1703, 1184)

        assertEquals(2, GameLayout.joinWrapped(listOf(speaker, dialogue)).size)
    }

    /** Measured: a name read as tall as the dialogue under it, 52 px apart. */
    @Test
    fun `a name beside its affiliation is never joined to the dialogue`() {
        val name = line("チナツ", 429, 977, 678, 1061)
        val affiliation = line("風紀委員会", 678, 977, 980, 1061)
        val dialogue = line("え、ええと……", 410, 1110, 871, 1180)

        assertEquals(3, GameLayout.joinWrapped(listOf(name, affiliation, dialogue)).size)
    }

    @Test
    fun `a sentence over three lines is one utterance`() {
        val first = line("安全に、そして確実に手元に届くよう、配送業者を手配したからな。", 433, 1122, 2300, 1178)
        val second = line("もちろん、選定にも抜かりはない。最も安いのに、最も早く届けてくれるところに依頼", 433, 1214, 2773, 1270)
        val third = line("した。", 440, 1306, 575, 1359)

        assertEquals(1, GameLayout.joinWrapped(listOf(first, second, third)).size)
    }

    /** The name's box and the dialogue's were the same height; the type is not. */
    @Test
    fun `a name is not joined to dialogue whose box grew to its height`() {
        val name = GameLine("美奈玄龍門", TextBounds(398, 974, 989, 1061, CoordinateSpace.SCREEN), 5.0, typeHeight = 66)
        val dialogue = GameLine("これでいい。", TextBounds(436, 1110, 1137, 1182, CoordinateSpace.SCREEN), 5.0, typeHeight = 46)

        assertEquals(2, GameLayout.joinWrapped(listOf(name, dialogue)).size)
    }

    /** Measured: the first line read without its leading 「……」 began 83 px in. */
    @Test
    fun `a first line read without its opening ellipsis is still joined`() {
        val first = line("良い機会ですので、覚えておいてください。", 519, 1122, 1717, 1179)
        val second = line("この業者は文字通り、一番安くて早いところではあります。", 436, 1213, 2619, 1270)

        assertEquals(1, GameLayout.joinWrapped(listOf(first, second)).size)
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
