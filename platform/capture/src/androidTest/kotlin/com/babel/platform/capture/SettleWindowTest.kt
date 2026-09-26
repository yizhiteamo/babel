package com.babel.platform.capture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.common.BabelLogger
import com.babel.core.model.LanguageTag
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextRegion
import com.babel.platform.screen.ScreenFrameSource
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long the scanner waits for a moving screen to stop.
 *
 * A frame read mid-scroll puts the outgoing screen's text across the incoming
 * one, so waiting is not optional. Waiting **long enough** turned out to be a
 * separate question: the first version gave up after three looks, and on the
 * emulator a `never came to rest` preceded every single scroll — the scan that
 * did the work then had to wait for the next event to start it.
 *
 * Measured with the limit lifted out of the way, over gestures checked to have
 * actually moved the page: 2 looks twice (514ms), 3 twice (1015ms), **4 twice
 * (1541ms)**, and never a fifth. So three does clip the tail — and widening it
 * to cover the tail was measured and bought nothing, because the first scan
 * after a scroll is invalidated whatever it does (`SETTLE_ATTEMPTS`). These
 * tests therefore pin the limit that is there, including the case it drops.
 *
 * Instrumentation rather than a JVM test because `FrameSignature` reads real
 * pixels, and this module's unit tests have no Robolectric — the fake frame
 * source in `CaptureTextSourceTest` can only return null, which is why the
 * settling path had no test until now.
 *
 * ```
 * adb shell am instrument -w -e class com.babel.platform.capture.SettleWindowTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SettleWindowTest {

    /** Hands out one prepared frame per call, then repeats the last for ever. */
    private class StagedFrames(private val shades: List<Int>) : ScreenFrameSource {
        override val isSupported = true
        override val isAvailable = true
        var handed = 0
            private set

        override suspend fun latestFrame(): Bitmap {
            val shade = shades[minOf(handed, shades.lastIndex)]
            handed += 1
            return Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
                .apply { eraseColor(shade) }
        }
    }

    private class RecordingReader : PageReader {
        var reads = 0
            private set

        override suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Boolean) {
            reads += 1
        }
    }

    private class UnusedRecognizer : TextRecognizer {
        override fun languageOf(text: String) = LanguageTag("ja")
        override suspend fun recognize(frame: Bitmap) = emptyList<RecognizedLine>()
    }

    private fun scan(shades: List<Int>): Pair<RecordingReader, StagedFrames> {
        val frames = StagedFrames(shades)
        val pages = RecordingReader()
        val source = CaptureTextSource(frames, pages, UnusedRecognizer(), BabelLogger.NoOp)
        runBlocking { source.scanOnce(packageName = "com.example.reader") }
        return pages to frames
    }

    @Test
    fun aScreenThatStopsWithinThreeLooksIsRead() = runBlocking {
        // Changing, changing, then still — a fling that has nearly finished
        // when the scan starts. Settles on the third look, the last one the
        // budget allows.
        val (pages, frames) = scan(listOf(BLACK, WHITE, WHITE))

        assertEquals(3, frames.handed, "it should have needed all three looks")
        assertEquals(1, pages.reads, "the settled frame should have been read")
    }

    /**
     * And one that needs a fourth is deliberately left behind.
     *
     * Two of six real settles did need a fourth, so this is a real page being
     * dropped, not a hypothetical. Covering them costs a wider window, and a
     * wider window was measured against this one over four drags each: 3.40s
     * mean against 3.24s — no better, because a fling keeps firing scroll
     * events and the scan in flight is thrown away regardless of how patient it
     * is. The next tick picks the page up, and that is cheaper than waiting.
     */
    @Test
    fun aScreenStillMovingAfterThreeLooksIsLeftToTheNextTick() = runBlocking {
        val (pages, frames) = scan(listOf(BLACK, WHITE, BLACK, WHITE, WHITE))

        assertEquals(3, frames.handed, "three looks and no more")
        assertEquals(0, pages.reads)
    }

    @Test
    fun aScreenAlreadyStillIsReadOnTheSecondLook() = runBlocking {
        // The ordinary case, and the reason the budget is not simply generous:
        // nothing should wait when there is nothing to wait for.
        val (pages, frames) = scan(listOf(WHITE))

        assertEquals(2, frames.handed, "one look to have something to compare against, one to agree")
        assertEquals(1, pages.reads)
    }

    @Test
    fun aScreenThatNeverStopsIsLeftToTheNextTick() = runBlocking {
        // A video, an animation. Reading it would smear one frame's text over
        // another's, and the budget has to end somewhere.
        val (pages, _) = scan(listOf(BLACK, WHITE, BLACK, WHITE, BLACK, WHITE, BLACK, WHITE, BLACK))

        assertEquals(0, pages.reads, "a screen still moving should not be read")
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 480
        const val BLACK = 0xFF101010.toInt()
        const val WHITE = 0xFFF0F0F0.toInt()
    }
}
