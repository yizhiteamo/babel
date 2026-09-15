package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.LanguageTag
import com.babel.core.testing.RecordingLogger
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextRegion
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureTextSourceTest {

    /** Screenshots fail for ordinary reasons: throttling, a secure window. */
    private class NoFrames : com.babel.platform.screen.ScreenFrameSource {
        override val isAvailable = true
        var calls = 0
        override suspend fun latestFrame(): Bitmap? {
            calls++
            return null
        }
    }

    private class UnusedRecognizer : TextRecognizer {
        override fun languageOf(text: String) = LanguageTag("ja")
        var called = false
        override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
            called = true
            return emptyList()
        }
    }

    /** Records whether a page was read at all; there is nothing to read here. */
    private class UnusedPageReader : PageReader {
        var called = false
        override suspend fun read(frame: Bitmap): List<TextRegion> {
            called = true
            return emptyList()
        }
    }

    /**
     * A scan that cannot get a picture must be a no-op. Screenshots fail for
     * ordinary reasons — throttling, a secure window — and treating that as
     * "nothing on screen" would drop every translation currently showing.
     *
     * What this pins down is narrower than it looks, and the limit is worth
     * knowing: it catches an emitted `Cleared` (verified by breaking it), but
     * not a `publish(emptyList())`, which emits nothing while no elements are
     * tracked. Covering that needs a real frame, which needs a real `Bitmap`,
     * which a JVM test does not have — so it is verified on device instead.
     */
    @Test
    fun `no frame produces no events`() = runTest(UnconfinedTestDispatcher()) {
        val frames = NoFrames()
        val recognizer = UnusedRecognizer()
        val pages = UnusedPageReader()
        val source = CaptureTextSource(frames, pages, recognizer, RecordingLogger())

        val seen = mutableListOf<TextSourceEvent>()
        val collector = backgroundScope.launch { source.events().collect(seen::add) }

        source.scanOnce(packageName = "com.example.reader")

        assertTrue(seen.isEmpty(), "expected no events, got $seen")
        assertTrue(!pages.called, "the page must not be read without a frame")
        assertTrue(!recognizer.called, "recognition should not run without a frame")
        assertTrue(frames.calls == 1)
    }
}
