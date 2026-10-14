package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals

/** Blue Archive's MomoTalk: a chat of bubbles, each sender's name over the first. */
class MomoTalkTest {

    private fun line(text: String, l: Int, t: Int, w: Int, h: Int, type: Int, e: Double = 0.0) =
        GameLine(text, TextBounds(l, t, l + w, t + h, CoordinateSpace.SCREEN), e, typeHeight = type)

    private val above = line("はあ、本当にもう", 1924, 268, 328, 45, 41)
    private val kei = line("ケイ", 1902, 377, 75, 36, 36)
    private val bubble = line("寮に着きました", 1922, 443, 292, 41, 41)

    @Test
    fun `a sender's name is not joined into the bubble under it`() {
        val joined = GameLayout.joinWrapped(listOf(above, kei, bubble))

        assertEquals(listOf("はあ、本当にもう", "ケイ", "寮に着きました"), joined.map { it.text })
    }

    @Test
    fun `a short reply in a chat is dialogue, the title bar is not`() {
        val hai = line("はい", 1924, 739, 76, 37, 37)
        val daily = line("デイリーやるの忘れてました!", 1923, 1129, 571, 41, 40)
        val title = line("食MomoTalk ?", 468, 142, 452, 56, 56)
        val lines = listOf(above, kei, bubble, hai, daily, title)

        val dialogue = GameLayout.dialogue(lines)

        assertEquals(true, hai in dialogue && bubble in dialogue && kei in dialogue)
        assertEquals(true, title in GameLayout.controls(lines, frameHeight = 1440, frameWidth = 3200, otherScript = true))
    }

    @Test
    fun `a one-letter exclamation in the bubbles' column is kept`() {
        val a = line("あ!", 1907, 834, 96, 44, 39)
        val daily = line("デイリーやるの忘れてました!", 1923, 1129, 571, 41, 40)
        val loneMark = line("あ!", 300, 834, 96, 44, 39)

        val kept = GameLayout.select(listOf(a, daily, loneMark), frameHeight = 1440, frameWidth = 3200)

        assertEquals(listOf(a, daily), kept)
    }

    @Test
    fun `a name in the chat list is not joined into its last message`() {
        val name = line("カスミ(水着)", 836, 418, 288, 45, 45)
        val message = line("居場所は送っておくから、気が...", 834, 484, 622, 41, 40)

        assertEquals(listOf(name.text, message.text), GameLayout.joinWrapped(listOf(name, message)).map { it.text })
    }

    @Test
    fun `a right bubble's reply lines up with the bubbles on its side`() {
        val good = line("それはよかった", 2410, 559, 314, 53, 38)
        val what = line("どうかした?", 2454, 956, 239, 39, 39)
        val important = line("あ、うん大事なことだからね", 2073, 1250, 629, 41, 41)

        assertEquals(true, good in GameLayout.dialogue(listOf(good, what, important)))
    }

    @Test
    fun `a sender's name is not joined into a short reply under it`() {
        val name = line("ケイ", 1902, 943, 75, 36, 36)
        val hai = line("はい", 1924, 1011, 79, 37, 37)

        assertEquals(listOf("ケイ", "はい"), GameLayout.joinWrapped(listOf(name, hai)).map { it.text })
    }

    @Test
    fun `a name in larger type is not joined into the message under it`() {
        val name = line("アリス (臨戦)", 836, 945, 286, 45, 45)
        val message = line("先生!", 834, 1009, 105, 43, 40)

        assertEquals(listOf(name.text, message.text), GameLayout.joinWrapped(listOf(name, message)).map { it.text })
    }

    @Test
    fun `short replies scrolled into a remembered column are dialogue`() {
        val seen = listOf(
            line("はあ、本当にもう", 1924, 268, 328, 45, 41),
            line("寮に着きました", 1922, 443, 292, 41, 41),
            line("はい", 1924, 739, 76, 37, 37),
            line("デイリーやるの忘れてました!", 1923, 1129, 571, 41, 40),
        )
        val columns = GameLayout.chatColumns(seen)
        val ta = line("た", 1922, 300, 40, 40, 39)
        val ima = line("いま", 1923, 520, 80, 40, 39)
        val name = line("ケイ", 1902, 460, 75, 36, 36)

        val kept = GameLayout.select(listOf(ta, ima, name), frameHeight = 1440, frameWidth = 3200, columns = columns)
        val dialogue = GameLayout.dialogue(kept, columns = columns)

        assertEquals(true, ta in dialogue && ima in dialogue)
        assertEquals(setOf(name), GameLayout.speakers(kept, columns = columns, senders = setOf("ケイ")))
    }

    @Test
    fun `a screen of short replies under one sender's name is dialogue`() {
        val k1 = line("ケイ", 1902, 140, 75, 36, 36)
        val sense = line("せんせ", 1922, 200, 120, 40, 39)
        val k2 = line("ケイ", 1902, 300, 75, 36, 36)
        val ta = line("た", 1922, 360, 40, 40, 39)
        val suke = line("すけて", 1922, 450, 120, 40, 39)

        val lines = listOf(k1, sense, k2, ta, suke)
        val kept = GameLayout.select(lines, frameHeight = 1440, frameWidth = 3200)
        val dialogue = GameLayout.dialogue(kept)

        assertEquals(true, sense in dialogue && ta in dialogue && suke in dialogue)
        assertEquals(setOf(k1, k2), GameLayout.speakers(kept))
    }

    @Test
    fun `a button whose right edge happens to meet a message is not dialogue`() {
        val newest = line("最新", 1315, 285, 72, 37, 37)
        val preview = line("割い日が続くが、息災かえ?", 834, 660, 538, 41, 40)
        val title = line("未読メッセージ(337)(", 669, 278, 499, 56, 54)
        val name = line("カスミ(水着)", 836, 418, 288, 45, 45)
        val message = line("居場所は送っておくから、気が...", 834, 484, 622, 41, 40)

        val dialogue = GameLayout.dialogue(listOf(newest, preview, title, name, message))

        assertEquals(false, newest in dialogue || title in dialogue)
    }

    @Test
    fun `a label in a chat's column is a message unless it is a sender`() {
        val seen = listOf(
            line("はあ、本当にもう", 1924, 268, 328, 45, 41),
            line("寮に着きました", 1922, 443, 292, 41, 41),
            line("はい", 1924, 739, 76, 37, 37),
            line("デイリーやるの忘れてました!", 1923, 1129, 571, 41, 40),
        )
        val columns = GameLayout.chatColumns(seen)
        val k1 = line("ケイ", 1902, 564, 75, 36, 36)
        val un = line("ん?", 1921, 626, 72, 43, 38)
        val wait = line("待ってください", 1921, 722, 291, 46, 40)
        val k2 = line("ケイ", 1902, 831, 74, 38, 35)
        val hmm = line("んー", 1923, 901, 80, 38, 38)
        val sensei = line("先生", 1921, 997, 82, 41, 41)
        val free = line("いま暇してますよね?", 1923, 1096, 406, 40, 40)
        val lines = listOf(k1, un, wait, k2, hmm, sensei, free)

        val speakers = GameLayout.speakers(GameLayout.select(lines, 1440, 3200, columns = columns), columns = columns)

        assertEquals(setOf(k1, k2), speakers)
    }

    @Test
    fun `a button with a speck read as a comma is not a sentence`() {
        assertEquals(false, GameLayout.isSentence("お知らせ、"))
        assertEquals(true, GameLayout.isSentence("居場所は送っておくから、"))
    }

    @Test
    fun `a menu bar is never dialogue`() {
        val bar = listOf("編成" to 1102, "ソーシャル" to 1269, "製造" to 1608, "ショップ" to 1809, "募集" to 2118)
            .map { (t, x) -> line(t, x, 1345, 40 * t.length, 40, 34) }
        val bubble = line("とにかく、今日は手伝ってくれてありが", 1891, 1272, 802, 46, 40)

        val dialogue = GameLayout.dialogue(bar + bubble)

        assertEquals(setOf(bubble), dialogue)
    }

    @Test
    fun `a lone reply on the player's side of a chat is dialogue`() {
        val seen = listOf(
            line("はあ、本当にもう", 1924, 268, 328, 45, 41),
            line("寮に着きました", 1922, 443, 292, 41, 41),
            line("デイリーやるの忘れてました!", 1923, 1129, 571, 41, 40),
        )
        val columns = GameLayout.chatColumns(seen)
        val reply = line("楽しかったよね", 2409, 699, 293, 41, 40)

        assertEquals(true, reply in GameLayout.dialogue(listOf(reply), columns = columns))
    }

    @Test
    fun `a title beside its help button is not a question`() {
        assertEquals(false, GameLayout.isSentence("生徒 ?"))
        assertEquals(true, GameLayout.isSentence("掃除?"))
    }
}
