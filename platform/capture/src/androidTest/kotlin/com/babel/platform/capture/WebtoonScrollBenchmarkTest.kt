package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.domain.vision.TextRegion
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What reusing a scrolled balloon's reading is actually worth.
 *
 * A user called scrolling a webtoon slow, and the figure behind that was 4.3s
 * of recognition per swipe with 2 of 10 translations served from cache.
 * `ScrolledBalloons` is the answer to it, and `ScrollMatchMeasurementTest`
 * established its premise — the boxes do line up. This measures the payoff, in
 * the units the complaint was made in.
 *
 * ## The comparison
 *
 * The same windows of the same page, read twice with the same models:
 *
 * - **remembering** — one reader across the whole scroll, as the app runs it
 * - **forgetting** — a fresh reader for every window, which is what the app did
 *   before, reproduced rather than quoted from an old log
 *
 * The forgetting half keeps the **same loaded models** and swaps only the
 * reader around them. Building a whole new one per screen was the first version
 * and it was not a fair baseline: it reloaded the ONNX sessions every time, a
 * cost the old app never paid, and charged it to the behaviour being replaced.
 *
 * Recognised text is **not** printed: unlike `PageTextDumpTest`, the strings are
 * not the measurement here, and the material is somebody's copyright
 * (`docs/systems/privacy.md`).
 *
 * Prints; asserts nothing. It is a measurement, and a threshold on a timing
 * would fail on a loaded emulator rather than on a regression.
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * # manga-ocr lives in the app's files dir; the test package needs its own copy
 * #   from /sdcard/Android/data/com.babel/files/models
 * #   to   /sdcard/Android/data/com.babel.platform.capture.test/files/models
 * adb shell am instrument -w -e class \
 *   com.babel.platform.capture.WebtoonScrollBenchmarkTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep WEBTOON
 * ```
 */
@RunWith(AndroidJUnit4::class)
class WebtoonScrollBenchmarkTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun scrollingAWebtoonWithAndWithoutReuse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "comic-sample/$WEBTOON")
        if (!file.exists()) {
            println("WEBTOON skipped: needs $WEBTOON pushed")
            return@runBlocking
        }
        if (!MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp).isAvailable) {
            println("WEBTOON skipped: copy the manga-ocr models into this package's files/models")
            return@runBlocking
        }

        val page = BitmapFactory.decodeFile(file.absolutePath)
            ?.copy(Bitmap.Config.ARGB_8888, false) ?: return@runBlocking
        val scaled = Bitmap.createScaledBitmap(
            page,
            SCREEN_WIDTH,
            page.height * SCREEN_WIDTH / page.width,
            true,
        )
        if (scaled !== page) page.recycle()

        val windows = buildList {
            var offset = 0
            while (offset + VIEWPORT <= scaled.height && offset <= MAX_OFFSET) {
                add(offset)
                offset += STEP
            }
        }
        println("WEBTOON ${windows.size} screens, ${STEP}px apart, viewport $VIEWPORT")

        // Forgetting first, so the remembering run cannot benefit from warmer
        // weights than the thing it is being compared against.
        val without = run(context, scaled, windows, remembers = false)
        val with = run(context, scaled, windows, remembers = true)
        scaled.recycle()

        println("WEBTOON")
        println("WEBTOON  a fresh reader each screen: ${without.totalMs}ms for ${without.regions} regions")
        println("WEBTOON  one reader across the scroll: ${with.totalMs}ms for ${with.regions} regions")
        val saved = without.totalMs - with.totalMs
        val percent = if (without.totalMs > 0) saved * 100 / without.totalMs else 0
        println("WEBTOON  saved ${saved}ms of ${without.totalMs}ms ($percent%)")

        // The other half of the win, and the one the user would feel: a reused
        // reading is the *same string*, so the translation cache hits it. A
        // re-read comes back slightly different and misses.
        val repeats = with.texts.size - with.texts.toSet().size
        println("WEBTOON  ${with.texts.size} readings, ${with.texts.toSet().size} distinct")
        println("WEBTOON  $repeats would be served from the translation cache")
    }

    private data class Run(val totalMs: Long, val regions: Int, val texts: List<String>)

    private suspend fun run(
        context: android.content.Context,
        scaled: Bitmap,
        windows: List<Int>,
        remembers: Boolean,
    ): Run {
        val label = if (remembers) "remembering" else "forgetting"

        // Loaded once and shared by every reader in this half. The models are
        // what cost seconds to load; the reader around them holds only the
        // memory of the last screen, which is the thing under test.
        val general = MlKitTextRecognizer(BabelLogger.NoOp)
        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        val recognizer = BubbleRecognizer(
            MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp),
            general,
        )
        fun reader() = DetectingPageReader(
            detector = detector,
            recognizer = recognizer,
            fallback = GroupingPageReader(general),
            logger = BabelLogger.NoOp,
        )

        var reader = reader()
        var total = 0L
        var regions = 0
        val texts = mutableListOf<String>()

        for (offset in windows) {
            // A new reader per screen, around the models already loaded: what
            // the old code amounted to, where nothing carried across a scroll
            // and every balloon was read again.
            if (!remembers) reader = reader()

            val window = Bitmap.createBitmap(scaled, 0, offset, SCREEN_WIDTH, VIEWPORT)
            val found = mutableListOf<TextRegion>()
            val started = System.currentTimeMillis()
            reader.read(window) { found += it }
            val elapsed = System.currentTimeMillis() - started
            window.recycle()

            total += elapsed
            regions += found.size
            texts += found.map { it.text }
            println("WEBTOON  $label, offset $offset: ${found.size} regions in ${elapsed}ms")
        }
        reader.release()
        return Run(total, regions, texts)
    }

    private companion object {
        const val WEBTOON = "jap-mag-09.jpg"
        const val SCREEN_WIDTH = 1080
        const val VIEWPORT = 1700
        const val STEP = 700

        /** Six screens: long enough to be a scroll, short enough to sit through. */
        const val MAX_OFFSET = 700 * 5
    }
}
