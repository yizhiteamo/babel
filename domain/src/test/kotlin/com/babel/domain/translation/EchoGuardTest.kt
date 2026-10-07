package com.babel.domain.translation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The strings are the translations measured on a pixiv character sheet. */
class EchoGuardTest {

    private val rendered = "奉日本、彩芭……您的侄女·最中的主人，作为修行的一环，寄居在伯父的别宅。"

    @Test
    fun `a paraphrase of another line's rendering is an echo`() {
        assertTrue(EchoGuard.repeatsAnother("奉日本、彩芭……两位的侄女·摩纳卡为了修行，寄住在伯父的别家。", listOf(rendered)))
        assertTrue(EchoGuard.repeatsAnother("奉日本、彩芭……大人的侄女·最中的主人，作为修行的一环寄居在伯父的别宅。", listOf(rendered)))
    }

    @Test
    fun `a different description is not`() {
        assertFalse(EchoGuard.repeatsAnother("献给日本·岭幻“彩芭的伯父麦与马龙的主人，精通咒术与管狐", listOf(rendered)))
        assertFalse(EchoGuard.repeatsAnother("【正和】神父的随从二对波伊的主家奉日本家恩，照顾着彩芭。", listOf(rendered)))
    }

    /** A name repeated on purpose is what the context is for. */
    @Test
    fun `a short repeated name is not an echo`() {
        assertFalse(EchoGuard.repeatsAnother("奉日本彩芭", listOf("奉日本彩芭")))
    }

    @Test
    fun `nothing rendered yet is nothing to echo`() {
        assertFalse(EchoGuard.repeatsAnother(rendered, emptyList()))
    }
}
