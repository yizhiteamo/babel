package com.babel.platform.capture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
import com.babel.core.model.CoordinateSpace
import com.babel.domain.vision.RecognizedLine
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * When the comic recogniser's answer is used, and when it is thrown away.
 *
 * Two failures live here and only one of them was ever handled. manga-ocr
 * declines crops it cannot make sense of, which was covered. It also does not
 * fail on a language it was not built for — given the English balloons on
 * `jap-mag-08` it returned `WindrisntthatSamantha2Thebig.hatThettbooksting...`,
 * and that was translated and drawn in an opaque box over text the reader could
 * already read (`docs/milestones/v2.md`).
 *
 * An instrumentation test rather than a JVM one because `recognize` takes a
 * `Bitmap`, which a JVM test has no way to make. Nothing here loads a model:
 * both engines are stood in for, which is the point — the decision under test
 * is which answer wins, not what either engine says.
 */
@RunWith(AndroidJUnit4::class)
class BubbleRecognizerTest {

    private fun frame(): Bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)

    private fun line(text: String) = RecognizedLine(
        text = text,
        bounds = TextBounds(0, 0, 10, 10, CoordinateSpace.SCREEN),
    )

    private class Manga(
        override val isAvailable: Boolean,
        private val lines: List<RecognizedLine>,
    ) : BalloonEngine {
        var calls = 0
        override fun languageOf(text: String) = LanguageTag("ja")
        override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
            calls++
            return lines
        }
        override suspend fun release() = Unit
    }

    private class General(private val lines: List<RecognizedLine>) : TextRecognizer {
        var calls = 0
        override fun languageOf(text: String) = LanguageTag("ja")
        override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
            calls++
            return lines
        }
    }

    @Test
    fun japaneseFromTheComicEngineIsKept() = runBlocking {
        val manga = Manga(isAvailable = true, lines = listOf(line("わたしの")))
        val general = General(listOf(line("should not be asked")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertEquals(listOf("わたしの"), read.map { it.text })
        assertEquals(0, general.calls, "the second engine should not have been asked")
    }

    @Test
    fun kanjiWithoutKanaIsStillJapaneseScript() = runBlocking {
        // Han alone is an ambiguous *language* claim and is not one here: the
        // question is whether a Japanese recogniser produced Japanese writing.
        val manga = Manga(isAvailable = true, lines = listOf(line("懺悔室")))
        val general = General(listOf(line("should not be asked")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertEquals(listOf("懺悔室"), read.map { it.text })
        assertEquals(0, general.calls)
    }

    @Test
    fun inventedLatinIsThrownAwayForTheGeneralEngine() = runBlocking {
        // The real page-08 output. It is not empty, so the older check passed
        // it straight through.
        val manga = Manga(
            isAvailable = true,
            lines = listOf(line("WindrisntthatSamantha2Thebig.hatThettbookstingoks")),
        )
        val general = General(listOf(line("Wait, isn't that Samantha?")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertEquals(listOf("Wait, isn't that Samantha?"), read.map { it.text })
        assertEquals(1, manga.calls, "the comic engine is still asked first")
        assertEquals(1, general.calls)
    }

    @Test
    fun nothingReadFallsBackAsItAlwaysDid() = runBlocking {
        val manga = Manga(isAvailable = true, lines = emptyList())
        val general = General(listOf(line("いくらでも使ってください")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertEquals(listOf("いくらでも使ってください"), read.map { it.text })
        assertEquals(1, general.calls)
    }

    @Test
    fun punctuationAloneIsNotAReading() = runBlocking {
        val manga = Manga(isAvailable = true, lines = listOf(line("......!?")))
        val general = General(listOf(line("…あ")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertEquals(listOf("…あ"), read.map { it.text })
    }

    @Test
    fun withoutTheModelTheGeneralEngineAnswersDirectly() = runBlocking {
        // No models on the device is a working configuration, not a broken one,
        // and in it nothing should be asked twice.
        val manga = Manga(isAvailable = false, lines = listOf(line("unused")))
        val general = General(listOf(line("Hello")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertEquals(listOf("Hello"), read.map { it.text })
        assertEquals(0, manga.calls)
        assertEquals(1, general.calls, "the general engine must not be asked twice")
    }

    @Test
    fun oneJapaneseLineIsEnoughToKeepTheReading() = runBlocking {
        // A balloon read as several lines where only some carry kana is still a
        // Japanese reading; the fallback is for an answer with none at all.
        val manga = Manga(isAvailable = true, lines = listOf(line("!?"), line("はい")))
        val general = General(listOf(line("should not be asked")))

        val read = BubbleRecognizer(manga, general).recognize(frame())

        assertTrue(read.map { it.text } == listOf("!?", "はい"), "got ${read.map { it.text }}")
        assertEquals(0, general.calls)
    }
}
