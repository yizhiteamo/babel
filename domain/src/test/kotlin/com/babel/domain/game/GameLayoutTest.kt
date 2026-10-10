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
        // The second box takes in more than its type; the type is one size.
        val first = line("う、ウソだろ?", 435, 1122, 837, 1178).copy(typeHeight = 55)
        val second = line("こんなすごい演奏を、女子高生が…!?", 414, 1208, 1558, 1275).copy(typeHeight = 55)

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
        // As measured: the boxes nearly one height, the type not.
        val speaker = line("アコ", 426, 980, 595, 1056).copy(typeHeight = 68)
        val dialogue = line("それが、万魔殿が……", 409, 1115, 1703, 1184).copy(typeHeight = 55)

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
    fun `Fate Grand Order's wider line spacing still wraps`() {
        val first = GameLine("フォウ！", TextBounds(509, 1169, 758, 1236, CoordinateSpace.SCREEN), 5.0, typeHeight = 64)
        val second = GameLine("フォウ、フォーウ！", TextBounds(510, 1287, 1050, 1350, CoordinateSpace.SCREEN), 5.0, typeHeight = 63)

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
    fun `lettering on art out to the left of the dialogue is not a speaker`() {
        val sign = line("センター", 283, 930, 627, 1026, error = 30.0)
        val dialogue = line("山海経？玄武商会？", 430, 1121, 1521, 1177, error = 6.0)

        assertEquals(listOf(dialogue), GameLayout.select(listOf(sign, dialogue), frameHeight = 1440))
    }

    @Test
    fun `a Japanese game's AUTO and MENU are left alone`() {
        val auto = line("AUTO", 2510, 66, 2678, 120)
        val menu = line("MENU", 2799, 69, 2979, 117)
        val dialogue = line("山海経？玄武商会？", 430, 1121, 1521, 1177)

        assertEquals(listOf(dialogue), GameLayout.select(listOf(auto, menu, dialogue), frameHeight = 1440, frameWidth = 3200))
    }

    @Test
    fun `a control drawn with arrows is still a control`() {
        val skip = line("SKIP»", 2560, 30, 2850, 110)
        val dialogue = line("フォウ！フォウ、フォーウ！", 509, 1169, 1050, 1236)

        assertEquals(listOf(dialogue), GameLayout.select(listOf(skip, dialogue), frameHeight = 1440, frameWidth = 3200))
    }

    @Test
    fun `an English game's MENU is translated`() {
        val menu = line("MENU", 2799, 69, 2979, 117)
        val dialogue = line("Where are you going?", 430, 1121, 1521, 1177)

        assertEquals(2, GameLayout.select(listOf(menu, dialogue), frameHeight = 1440, frameWidth = 3200).size)
    }

    @Test
    fun `a Latin word in the middle of the screen is not a control`() {
        val choice = line("OK", 1500, 700, 1700, 760)
        val dialogue = line("よろしいですか？", 430, 1121, 1521, 1177)

        assertEquals(setOf<GameLine>(), GameLayout.controls(listOf(choice, dialogue), frameHeight = 1440, frameWidth = 3200))
    }

    /** Fate/Grand Order's profile text, on a patterned panel: kept whole, not one line in two. */
    @Test
    fun `a paragraph over a patterned panel is kept whole`() {
        val first = line("謎めいた、暗い色の水着を纏う未亡人。", 946, 337, 1679, 377, error = 8.8)
        val second = line("唇からこぼれる言葉は謎めいて、", 946, 384, 1553, 424, error = 11.4)
        val third = line("冷ややかで、忍び寄る猟奇を予感させる。", 946, 432, 1721, 472, error = 20.8)

        val kept = GameLayout.select(listOf(first, second, third), frameHeight = 1440, frameWidth = 3200)

        assertEquals(3, kept.size)
        assertEquals(1, GameLayout.joinWrapped(kept).size)
    }

    @Test
    fun `the pieces of a spaced-out line of dialogue are all dialogue`() {
        val first = line("塩基配列", 733, 1135, 1018, 1200)
        val second = line("ヒトゲノムと確認。", 1168, 1135, 1740, 1200)
        val third = line("霊器属性", 733, 1225, 1018, 1290)
        val button = line("戻る", 2900, 60, 3100, 120)

        assertEquals(setOf(first, second, third), GameLayout.dialogue(listOf(first, second, third, button)))
    }

    @Test
    fun `a line of dialogue cut at a wide space is joined back`() {
        val first = GameLine("貴方は本日", TextBounds(733, 1225, 1018, 1290, CoordinateSpace.SCREEN), 5.0, typeHeight = 60)
        val second = GameLine("最後の来館者です。", TextBounds(1168, 1225, 1740, 1290, CoordinateSpace.SCREEN), 5.0, typeHeight = 60)

        val joined = GameLayout.joinRows(listOf(first, second), GameLayout.dialogue(listOf(first, second)))

        assertEquals(listOf("貴方は本日　最後の来館者です。"), joined.map { it.text })
    }

    @Test
    fun `a name and its affiliation stay apart`() {
        val name = GameLine("チナツ", TextBounds(429, 977, 678, 1061, CoordinateSpace.SCREEN), 5.0, typeHeight = 70)
        val affiliation = GameLine("風紀委員会", TextBounds(678, 977, 980, 1061, CoordinateSpace.SCREEN), 5.0, typeHeight = 55)
        val dialogue = GameLine("いえ……あんこ抜きのどら焼きを。", TextBounds(435, 1122, 2078, 1179, CoordinateSpace.SCREEN), 5.0, typeHeight = 54)
        val lines = listOf(name, affiliation, dialogue)

        assertEquals(3, GameLayout.joinRows(lines, GameLayout.dialogue(lines)).size)
    }

    /** Fate/Grand Order's battle tutorial, centred over the battlefield. */
    @Test
    fun `a centred instruction over the art is kept and joined`() {
        val heading = line("模擬戦闘を開始します", 1228, 372, 1970, 442, error = 17.5)
        val first = line("Attackをタッチして、", 1185, 520, 1971, 598, error = 30.3)
        val second = line("サーヴァントへの行動指示を", 1116, 597, 2082, 675, error = 46.8)
        val third = line("開始してください", 1305, 673, 1897, 742, error = 41.0)
        val lines = listOf(heading, first, second, third)

        val kept = GameLayout.select(lines, frameHeight = 1440, frameWidth = 3200)

        assertEquals(4, kept.size)
        assertEquals(1, GameLayout.joinWrapped(kept).size)
    }

    @Test
    fun `a name tab set out to the left of the dialogue is a speaker`() {
        val tab = line("レフ", 368, 1006, 550, 1075, error = 13.0)
        val dialogue = line("ふむ、君と。召集された48名の適格者、その最後の一人か。", 475, 1159, 2284, 1237, error = 6.7)

        assertEquals(2, GameLayout.select(listOf(tab, dialogue), frameHeight = 1440, frameWidth = 3200).size)
    }

    @Test
    fun `a speaker stays while the next line is still too short to keep`() {
        val tab = line("レフ", 368, 1003, 553, 1075, error = 12.8)
        val typing = line("「", 508, 1171, 565, 1234)

        val before = listOf(tab)
        assertEquals(listOf(tab), GameLayout.select(listOf(tab, typing), frameHeight = 1440, frameWidth = 3200, before = before))
        assertEquals(emptyList(), GameLayout.select(listOf(tab, typing), frameHeight = 1440, frameWidth = 3200))
    }

    @Test
    fun `dialogue starting a little after the name still has its speaker`() {
        val tab = line("レフ", 368, 1003, 553, 1075, error = 12.8)
        val dialogue = line("もし他に質問がなければ、管制室へ行こう。", 627, 1170, 1731, 1237)

        assertEquals(2, GameLayout.select(listOf(tab, dialogue), frameHeight = 1440, frameWidth = 3200).size)
    }

    @Test
    fun `lettering on art with nothing translated below stays out`() {
        val art = line("イペントアイテムを種得しましょう!", 1717, 954, 2525, 1017, error = 20.0)

        assertEquals(emptyList(), GameLayout.select(listOf(art), frameHeight = 1440))
    }
}
