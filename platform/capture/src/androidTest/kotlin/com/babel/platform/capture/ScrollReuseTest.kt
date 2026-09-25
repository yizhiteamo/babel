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
 * What the reader does with a screen it has seen part of before.
 *
 * Two behaviours meet here and both are invisible to the pure tests in
 * `:domain`: a scrolled balloon keeps the reading it already has
 * (`ScrolledBalloons`), and a balloon the viewport has cut is left for the
 * screen that shows it whole (`ClippedBalloons`). `ScrolledBalloonsTest` and
 * `ClippedBalloonsTest` cover the judgement, and `WebtoonScrollBenchmarkTest`
 * measures what it saves; neither would notice the wiring coming undone.
 *
 * No models and no comic: a fake detector places the balloons, so what the
 * recogniser is asked to read is exactly what the reader decided to ask for.
 * Instrumentation rather than a JVM test only because `Bitmap` is real here.
 *
 * The balloon positions are chosen so that a 700px scroll leaves every one of
 * them clear of both edges. That is not decoration — the first version of this
 * file used positions where a scroll landed a balloon on `top = 3`, and the
 * skip rule fired on it exactly as it should, which read as a regression until
 * the fixture was looked at. A detector would not report a box that has
 * scrolled off the screen either, so neither does this.
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
        override suspend fun release() = Unit
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

    private fun frame() = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)

    private fun readerFor(detector: TextDetector, engine: CountingEngine): DetectingPageReader {
        val general = UnusedRecognizer()
        return DetectingPageReader(
            detector = detector,
            recognizer = BubbleRecognizer(engine, general),
            fallback = GroupingPageReader(general),
            logger = BabelLogger.NoOp,
        )
    }

    /** Low enough on the page that a 700px scroll keeps all three on screen. */
    private val screen = listOf(
        box(120, 900, 300, 180),
        box(520, 1180, 260, 140),
        box(160, 1420, 340, 150),
    )

    /** The same balloons [by] pixels higher, each landing a few pixels off. */
    private fun scrolled(by: Int, jitter: List<Int> = listOf(0, 3, -2)) =
        screen.mapIndexed { index, it ->
            box(it.left + jitter[index], it.top - by + jitter[index], it.width, it.height)
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

        // Scrolled up by 700, with the jitter the detector really produces,
        // plus one balloon that has come into view.
        detector.bubbles = (scrolled(by = 700) + box(400, 1100, 280, 160)).map { bubble(it) }

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
            box(80, 190, 190, 410),
            box(430, 715, 520, 95),
            box(210, 1133, 145, 275),
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

    @Test
    fun aBalloonTheViewportCutsIsLeftForTheNextScreen() = runBlocking {
        val engine = CountingEngine()
        val detector = StagedDetector(screen.map { bubble(it) })
        val reader = readerFor(detector, engine)

        reader.read(frame()) { }
        assertEquals(3, engine.calls)

        // The same three, scrolled, plus one running off the bottom and one cut
        // by the top edge. Both are half a balloon: reading either costs a
        // recognition and a provider call to produce half a sentence, and the
        // top one was read whole on the screen before this.
        detector.bubbles = (
            scrolled(by = 700) +
                box(400, HEIGHT - 50, 280, 200) +
                box(700, 0, 260, 140)
            ).map { bubble(it) }

        val second = mutableListOf<TextRegion>()
        reader.read(frame()) { second += it }

        assertEquals(3, engine.calls, "neither cut balloon should have been read")
        assertEquals(3, second.size, "and neither should have reached the screen")
    }

    @Test
    fun aCutBalloonIsReadOnceItIsWhole() = runBlocking {
        val engine = CountingEngine()
        val detector = StagedDetector(screen.map { bubble(it) })
        val reader = readerFor(detector, engine)

        reader.read(frame()) { }

        // Only its top 100px are on screen; the balloon is 340 tall.
        detector.bubbles = (scrolled(by = 700) + box(400, HEIGHT - 100, 280, 340))
            .map { bubble(it) }
        reader.read(frame()) { }
        assertEquals(3, engine.calls, "still cut")

        // Scrolled another 300. The two balloons that stayed on screen carry
        // their readings and give the vote its two pairs; the third has gone
        // off the top, which a detector would simply not report. The cut one is
        // now wholly on screen at its full height — and nothing remembers it,
        // because a cut box is deliberately never stored as a yardstick.
        detector.bubbles = (
            scrolled(by = 1000).drop(1) + box(400, HEIGHT - 400, 280, 340)
            ).map { bubble(it) }

        val third = mutableListOf<TextRegion>()
        reader.read(frame()) { third += it }
        assertEquals(4, engine.calls, "the balloon should be read now that it is whole")
        assertEquals(3, third.size)
    }

    @Test
    fun aScreenThatCannotBePlacedReadsItsEdgesAsBefore() = runBlocking {
        val engine = CountingEngine()
        // Nothing precedes this screen, so there is no knowing whether the
        // balloon at the top is cut or simply sits at the top of the page.
        // Skipping it would mean never reading it, so this behaves exactly as
        // it did before the rule existed.
        val detector = StagedDetector((screen + box(700, 0, 260, 140)).map { bubble(it) })
        val reader = readerFor(detector, engine)

        val only = mutableListOf<TextRegion>()
        reader.read(frame()) { only += it }
        assertEquals(4, engine.calls, "an unplaceable screen reads everything on it")
        assertEquals(4, only.size)
    }

    private companion object {
        const val WIDTH = 1080
        const val HEIGHT = 1700
    }
}
