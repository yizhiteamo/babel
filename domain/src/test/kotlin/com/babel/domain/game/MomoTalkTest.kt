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
}
