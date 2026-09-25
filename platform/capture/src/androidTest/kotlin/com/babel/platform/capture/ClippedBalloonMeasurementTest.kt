package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.model.TextBounds
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How often the viewport cuts a balloon in half, and what that costs.
 *
 * Nothing handles this today: the detector clamps every box to the frame
 * (`OnnxBubbleDetector`), so half a balloon is read, translated and drawn like
 * any other. That is four separate losses — an encoder and decoder pass spent
 * on half a sentence, a paid provider call for it, a half-sentence on screen,
 * and, since a truncated box is a different height from the whole one, a
 * balloon that `ScrolledBalloons` cannot match on the next screen and so reads
 * again.
 *
 * The last one is already visible in `ScrollMatchMeasurementTest`: the two
 * balloons it failed to match stayed unmatched even at ±32px, and detector
 * jitter is a 4px effect. Truncation is the explanation that fits.
 *
 * ## What is being decided
 *
 * Skipping an edge-touching balloon costs one screen of delay, which is free
 * when the reader is scrolling towards it. It is **not** free if the balloon is
 * genuinely complete and simply sits at the edge of the page — the first
 * balloon of a webtoon, or any balloon on a page read whole rather than
 * scrolled. So this measures both shapes of material before a rule is written:
 *
 * - `jap-mag-09` — a webtoon, read through a scrolling viewport
 * - `jap-mag-01..08` — ordinary pages, read whole, which is what fit-to-screen
 *   reading gives the pipeline
 *
 * and, for the webtoon, whether an edge-touching box really was truncated:
 * the same balloon reappearing **taller** on the next screen is the proof, and
 * a box that never grows was complete all along.
 *
 * Prints; asserts nothing.
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * adb shell am instrument -w -e class \
 *   com.babel.platform.capture.ClippedBalloonMeasurementTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep CLIPPED
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ClippedBalloonMeasurementTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun measureHowOftenTheViewportCutsABalloon() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val samples = File(context.getExternalFilesDir(null), "comic-sample")
        if (!samples.isDirectory) {
            println("CLIPPED skipped: needs comic-sample pushed")
            return@runBlocking
        }

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        measureWebtoon(samples, detector)
        measureWholePages(samples, detector)
        detector.release()
    }

    // ---- the webtoon, through a scrolling viewport -------------------------

    private suspend fun measureWebtoon(samples: File, detector: OnnxBubbleDetector) {
        val file = File(samples, WEBTOON)
        if (!file.exists()) {
            println("CLIPPED skipped webtoon: needs $WEBTOON")
            return
        }
        val scaled = scaledToScreen(file) ?: return
        println("CLIPPED")
        println("CLIPPED === $WEBTOON, viewport $VIEWPORT, step $STEP ===")

        var previous: List<DetectedBubble> = emptyList()
        var total = 0
        var touching = 0
        var proven = 0
        var complete = 0
        var unknown = 0
        val edgeHeights = mutableListOf<Int>()

        var offset = 0
        while (offset + VIEWPORT <= scaled.height && offset <= MAX_OFFSET) {
            val boxes = detect(detector, scaled, offset)

            val edge = boxes.filter { it.text.touchesEdge(VIEWPORT) }
            total += boxes.size
            touching += edge.size
            edgeHeights += edge.map { it.text.height }

            // The detector reports the balloon outline as well as the
            // lettering. An outline that is wholly on screen is proof the words
            // inside it are, whatever the lettering box does — which would be a
            // far better test than "the box touches the edge", if it holds.
            for (one in edge) {
                val outline = one.balloon
                val verdict = when {
                    outline == null -> "no outline"
                    outline.touchesEdge(VIEWPORT) -> "outline also cut"
                    else -> "OUTLINE WHOLE — complete despite the edge"
                }
                println("CLIPPED    edge box ${one.text.height}px: $verdict")
            }

            // Was the previous screen's bottom-edge box really cut? The same
            // balloon reappears on this screen, shifted up by STEP, and if it
            // was cut it comes back taller. One that comes back the same height
            // was complete and would have been skipped for nothing.
            for (old in previous.filter { it.text.bottom >= VIEWPORT - EDGE }) {
                val again = boxes.firstOrNull {
                    abs(it.text.left - old.text.left) <= SAME &&
                        abs((it.text.top + STEP) - old.text.top) <= SAME * 4
                }
                val outline = old.balloon
                val said = when {
                    outline == null -> "no outline"
                    outline.touchesEdge(VIEWPORT) -> "outline also cut"
                    else -> "outline whole"
                }
                when {
                    again == null -> unknown += 1
                    again.text.height > old.text.height + SAME -> {
                        proven += 1
                        println("CLIPPED    followed: CUT (${old.text.height}px to ${again.text.height}px), $said")
                    }
                    else -> {
                        complete += 1
                        println("CLIPPED    followed: complete (${old.text.height}px), $said")
                    }
                }
            }

            println(
                "CLIPPED offset $offset: ${boxes.size} balloons, ${edge.size} touching an edge " +
                    edge.joinToString(prefix = "[", postfix = "]") {
                        "${it.text.height}px${if (it.text.top <= EDGE) " top" else ""}" +
                            if (it.text.bottom >= VIEWPORT - EDGE) " bottom" else ""
                    },
            )

            previous = boxes
            offset += STEP
        }
        scaled.recycle()

        println("CLIPPED")
        println("CLIPPED  $touching of $total detections touched an edge")
        println("CLIPPED  of the bottom-edge ones followed to the next screen:")
        println("CLIPPED    $proven came back taller — genuinely cut")
        println("CLIPPED    $complete came back the same height — would be skipped for nothing")
        println("CLIPPED    $unknown could not be followed")
        if (edgeHeights.isNotEmpty()) {
            val sorted = edgeHeights.sorted()
            println(
                "CLIPPED  edge box heights: min ${sorted.first()}, median " +
                    "${sorted[sorted.size / 2]}, max ${sorted.last()} of $VIEWPORT viewport",
            )
            // The fallback this needs: a balloon taller than the viewport can
            // never be whole, so skipping it would mean never reading it.
            val tallerThanViewport = edgeHeights.count { it >= VIEWPORT - EDGE }
            println("CLIPPED  $tallerThanViewport of them fill the viewport outright")
        }
    }

    // ---- ordinary pages, read whole ----------------------------------------

    private suspend fun measureWholePages(samples: File, detector: OnnxBubbleDetector) {
        val pages = samples
            .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.filter { it.name != WEBTOON }
            ?.sortedBy { it.name }
            .orEmpty()
        if (pages.isEmpty()) return

        println("CLIPPED")
        println("CLIPPED === whole pages: the cost of the same rule where nothing is cut ===")

        var total = 0
        var touching = 0
        for (file in pages) {
            val page = scaledToScreen(file) ?: continue
            val boxes = detector.detect(page)
            val edge = boxes.filter { it.text.touchesEdge(page.height) }
            total += boxes.size
            touching += edge.size
            println("CLIPPED ${file.name}: ${boxes.size} balloons, ${edge.size} touching an edge")
            page.recycle()
        }
        println("CLIPPED")
        println("CLIPPED  $touching of $total would be skipped on pages where nothing is cut")
    }

    // ---- helpers ------------------------------------------------------------

    private suspend fun detect(
        detector: OnnxBubbleDetector,
        scaled: Bitmap,
        offset: Int,
    ): List<DetectedBubble> {
        val window = Bitmap.createBitmap(scaled, 0, offset, SCREEN_WIDTH, VIEWPORT)
        return try {
            detector.detect(window)
        } finally {
            window.recycle()
        }
    }

    private fun TextBounds.touchesEdge(frameHeight: Int) =
        top <= EDGE || bottom >= frameHeight - EDGE

    /** At the size the device reads it, so the model sees production input. */
    private fun scaledToScreen(file: File): Bitmap? {
        val full = BitmapFactory.decodeFile(file.absolutePath)
            ?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        if (full.width <= SCREEN_WIDTH) return full
        val height = full.height * SCREEN_WIDTH / full.width
        return Bitmap.createScaledBitmap(full, SCREEN_WIDTH, height, true)
            .also { if (it !== full) full.recycle() }
    }

    private companion object {
        const val WEBTOON = "jap-mag-09.jpg"
        const val SCREEN_WIDTH = 1080
        const val VIEWPORT = 1700
        const val STEP = 700
        const val MAX_OFFSET = 700 * 5

        /** Within this of the frame's edge counts as touching it. */
        const val EDGE = 4

        /** The jitter two detections of one balloon land within. */
        const val SAME = 8
    }
}
