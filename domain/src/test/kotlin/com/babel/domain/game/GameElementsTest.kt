package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.Revision
import com.babel.core.model.RowGradient
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextSourceType
import com.babel.core.model.TranslationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameElementsTest {

    private val window = TextBounds(100, 50, 1700, 950, CoordinateSpace.SCREEN)

    private fun line(text: String, left: Int = 200, top: Int = 300, right: Int = 500, bottom: Int = 340) =
        GameLine(text, TextBounds(left, top, right, bottom, CoordinateSpace.WINDOW), backdropError = 2.0)

    @Test
    fun `sets all five source traits with durableLabel true for an interface label`() {
        val text = "ノーマルスキル"
        assertTrue(GameLayout.isLabel(text))

        val element = GameElements.toElement(
            key = "$text@8,12",
            line = line(text),
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 3L,
            kanaVotes = 0.0,
        )

        assertEquals(TextSourceType.GAME, element.sourceType)
        assertTrue(element.recognised)
        assertTrue(element.carriesContext)
        assertEquals(GameLayout.isLabel(text), element.durableLabel)
        assertTrue(element.durableLabel)
        assertTrue(element.keepsLatinWords)
        assertFalse(element.pausable)
        assertFalse(element.conversational)
    }

    @Test
    fun `sets durableLabel false for a punctuated dialogue sentence while keeping other game traits`() {
        val text = "ふふ。みなさん、ありがとうございます。"
        assertFalse(GameLayout.isLabel(text))

        val element = GameElements.toElement(
            key = "$text@10,27",
            line = line(text),
            style = SourceStyle.UNKNOWN,
            conversational = true,
            window = window,
            packageName = "com.example.game",
            session = 7L,
            kanaVotes = 30.0,
        )

        assertTrue(element.recognised)
        assertTrue(element.carriesContext)
        assertEquals(GameLayout.isLabel(text), element.durableLabel)
        assertFalse(element.durableLabel)
        assertTrue(element.keepsLatinWords)
        assertFalse(element.pausable)
        assertTrue(element.conversational)
        assertEquals(Revision(7L), element.revision)
    }

    @Test
    fun `grows bounds by backdrop margins and offsets by window coordinates`() {
        val raw = line("ステータス", left = 200, top = 300, right = 400, bottom = 340)
        val plain = GameElements.toElement(
            key = "k1",
            line = raw,
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 1L,
            kanaVotes = 0.0,
        )
        assertEquals(TextBounds(300, 350, 500, 390, CoordinateSpace.SCREEN), plain.bounds)

        val gradient = RowGradient(
            left = List(52) { 0xFF202020.toInt() },
            right = List(52) { 0xFF202020.toInt() },
            marginPx = 2,
            rowMarginPx = 6,
        )
        val erased = GameElements.toElement(
            key = "k1",
            line = raw,
            style = SourceStyle(backdrop = gradient),
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 1L,
            kanaVotes = 0.0,
        )
        assertEquals(TextBounds(298, 344, 502, 396, CoordinateSpace.SCREEN), erased.bounds)
    }

    @Test
    fun `marks kanji-only names as Japanese only once kanaVotes reaches threshold`() {
        val kanjiName = line("風紀委員会")

        val beforeVotes = GameElements.toElement(
            key = "name@1,1",
            line = kanjiName,
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 0L,
            kanaVotes = GameElements.JAPANESE_GAME_KANA - 0.1,
        )
        assertNull(beforeVotes.sourceLanguage)

        val afterVotes = GameElements.toElement(
            key = "name@1,1",
            line = kanjiName,
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 0L,
            kanaVotes = GameElements.JAPANESE_GAME_KANA,
        )
        assertEquals(GameElements.JAPANESE, afterVotes.sourceLanguage)

        val latin = GameElements.toElement(
            key = "auto@1,1",
            line = line("AUTO"),
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 0L,
            kanaVotes = 50.0,
        )
        assertNull(latin.sourceLanguage)
    }

    @Test
    fun `populates game speaker and row hints metadata`() {
        val regular = GameElements.toElement(
            key = "label@2,3",
            line = line("EXスキル"),
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 2L,
            kanaVotes = 25.0,
            speaker = false,
            row = listOf("ノーマルスキル", "パッシブスキル", "サブスキル"),
        )
        assertTrue(regular.id.value.startsWith("game:"))
        assertEquals("com.example.game", regular.source.packageName)
        assertEquals(
            mapOf(
                TranslationRequest.Hints.GAME to "com.example.game",
                TranslationRequest.Hints.ROW to "ノーマルスキル\nパッシブスキル\nサブスキル",
            ),
            regular.metadata,
        )

        val speaker = GameElements.toElement(
            key = "speaker@2,2",
            line = line("ヒナ"),
            style = SourceStyle.UNKNOWN,
            conversational = false,
            window = window,
            packageName = "com.example.game",
            session = 2L,
            kanaVotes = 25.0,
            speaker = true,
        )
        assertEquals(
            mapOf(
                TranslationRequest.Hints.GAME to "com.example.game",
                TranslationRequest.Hints.SPEAKER to "true",
            ),
            speaker.metadata,
        )
    }
}
