package com.babel.platform.capture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.common.BabelLogger
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextRegion
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * That a scrolled balloon is not read a second time.
 *
 * `ScrolledBalloonsTest` covers the judgement and `WebtoonScrollBenchmarkTest`
 * measures what it saves, but neither would notice the wiring coming undone —
 * a benchmark asserts nothing, and the pure test never touches the reader. This
 * is the one that fails if the reuse quietly stops happening.
 *
 * No models and no comic: a fake detector places the balloons, so what the
 * recogniser is asked to read is exactly what the reader decided to ask for.
 * Instrumentation rather than a JVM test only because `Bitmap` is real here.
 *
 * ```
 * adb shell am instrument -w -e class com.babel.platform.capture.ScrollReuseTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ScrollReuseTest {

    private fun box(left: Int, top: Int, width: Int, height: Int) = TextBounds(
        left = left,
        top = top,
        right = left + width,
        bottom = top + height,
        space = CoordinateSpace.SCREEN,
    )

    /** Hands back whatever the test says is on this screen. */
    private class StagedDetector(var bubbles: List<DetectedBubble>) : TextDetector {
        override val isAvailable = true
        override suspend fun detect(frame: Bitmap) = bubbles
    }

    /** Counts what it is asked to read, and says something different each time. */
    private class CountingEngine : BalloonEngine {
        override val isAvailable = true
        var calls = 0
        var released = 0
        override suspend fun release() {
            released += 1
        }

        override fun languageOf(text: String) = LanguageTag("ja")
        override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
            calls += 1
            return listOf(
                RecognizedLine(
                    text = "読み$calls",
                    bounds = TextBounds(0, 0, frame.width, frame.height, CoordinateSpace.SCREEN),
                ),
            )
        }
    }

    private class UnusedRecognizer : TextRecognizer {
        override fun languageOf(text: String) = LanguageTag("ja")
        override suspend fun recognize(frame: Bitmap) = emptyList<RecognizedLine>()
    }

    private fun bubble(box: TextBounds) = DetectedBubble(text = box, balloon = box, onArt = false)

    private fun frame() = Bitmap.createBitmap(1080, 1700, Bitmap.Config.ARGB_8888)

    private fun readerFor(detector: TextDetector, engine: CountingEngine): DetectingPageReader {
        val general = UnusedRecognizer()
        return DetectingPageReader(
            detector = detector,
            recognizer = BubbleRecognizer(engine, general),
            fallback = GroupingPageReader(general),
            logger = BabelLogger.NoOp,
        )
    }

    private val screen = listOf(
        box(120, 240, 300, 180),
        box(520, 700, 260, 140),
        box(160, 1180, 340, 220),
    )

    /** The same balloons 700px higher, each landing a few pixels off. */
    private fun scrolled(jitter: List<Int>) = screen.mapIndexed { index, it ->
        val off = jitter[index]
        box(it.left + off, it.top - 700 + off, it.width, it.height)
    }

    @Test
    fun aScrolledBalloonKeepsTheReadingItAlreadyHas() = runBlocking {
        val engine = CountingEngine()
        val detector = StagedDetector(screen.map { bubble(it) })
        val reader = readerFor(detector, engine)

        val first = mutableListOf<TextRegion>()
        reader.read(frame()) { first += it }
        assertEquals(3, engine.calls, "the first screen has to be read")
        assertEquals(3, first.size)

        // Scrolled up by 700, with the jitter the detector really produces.
        detector.bubbles = (scrolled(listOf(0, 3, -2)) + box(400, 1300, 280, 160))
            .map { bubble(it) }

        val second = mutableListOf<TextRegion>()
        reader.read(frame()) { second += it }

        assertEquals(4, engine.calls, "only the newly visible balloon should be read")
        assertEquals(4, second.size)

        // And the reused ones say what they said before, which is what makes
        // the translation cache hit rather than pay for the same line twice.
        val carried = first.map { it.text }
        assertTrue(
            second.map { it.text }.containsAll(carried),
            "expected ${second.map { it.text }} to still contain $carried",
        )
    }

    @Test
    fun aDifferentPageIsReadAgainInFull() = runBlocking {
        val engine = CountingEngine()
        val detector = StagedDetector(screen.map { bubble(it) })
        val reader = readerFor(detector, engine)

        reader.read(frame()) { }
        assertEquals(3, engine.calls)

        // Nothing in common: a chapter ending, or the user leaving for another
        // app. Guessing a shift here would put the last page's words on this
        // one, which is worse than the re-read this feature exists to avoid.
        detector.bubbles = listOf(
            box(80, 90, 190, 410),
            box(430, 615, 520, 95),
            box(210, 1333, 145, 275),
        ).map { bubble(it) }

        reader.read(frame()) { }
        assertEquals(6, engine.calls, "a page that changed has to be read from scratch")
    }

    @Test
    fun switchingMangaModeOffForgetsTheScreen() = runBlocking {
        val engine = CountingEngine()
        val detector = StagedDetector(screen.map { bubble(it) })
        val reader = readerFor(detector, engine)

        reader.read(frame()) { }
        assertEquals(3, engine.calls)

        // Releasing is what happens when the user turns manga mode off. Coming
        // back is a new screen, and reusing a reading of whatever they were
        // looking at before would be a reading of the wrong thing.
        reader.release()

        reader.read(frame()) { }
        assertEquals(6, engine.calls, "nothing should survive a release")
    }
}
