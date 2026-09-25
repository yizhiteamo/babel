package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.model.TextBounds
import com.babel.domain.vision.ScrolledBalloons
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether a balloon can be recognised as **the same balloon** after a scroll.
 *
 * Scrolling a webtoon costs 4.3s of recognition and eight provider calls per
 * swipe, measured on the device, because every balloon on the new screen is
 * read from scratch — including the ones that were fully read a moment ago. The
 * translation cache cannot help: it is keyed on the text, and a balloon read
 * twice comes back slightly different.
 *
 * Three attempts to cache **recognition** on what a balloon looks like all
 * failed, and the record says why: the detector runs on a 640x640 resize of the
 * whole screen, so a scrolled screen is different input and the box it returns
 * lands a few pixels differently every time. Its closing line names the one
 * thing not tried — *"matching balloons between consecutive frames by position
 * and scroll delta, rather than on the pixels it hands over"*
 * (`docs/milestones/v2.md`).
 *
 * That idea rests on a premise this measures: **after shifting by the scroll,
 * do the boxes line up?** If the same balloon comes back within a few pixels of
 * where it should be, matching works and recognition can be reused. If the
 * jitter is as large as the gaps between balloons, it cannot, and this is the
 * fourth failed attempt rather than the first good one.
 *
 * ## How the scroll is modelled
 *
 * The page is scaled to screen width once, then read through a viewport-sized
 * window at successive offsets — which is what the browser does. The detector's
 * own resize happens per window, independently, so the jitter this is measuring
 * is present and not smoothed away.
 *
 * Prints; asserts nothing.
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * adb shell am instrument -w -e class \
 *   com.babel.platform.capture.ScrollMatchMeasurementTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep SCROLLMATCH
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ScrollMatchMeasurementTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun measureWhetherBoxesLineUpAfterAScroll() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), "comic-sample/$WEBTOON")
        if (!file.exists()) {
            println("SCROLLMATCH skipped: needs $WEBTOON pushed")
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

        println("SCROLLMATCH page ${scaled.width}x${scaled.height}, viewport $VIEWPORT")
        println("SCROLLMATCH scroll step $STEP px")

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        var previous: List<TextBounds>? = null
        var previousOffset = 0

        var offset = 0
        while (offset + VIEWPORT <= scaled.height && offset <= MAX_OFFSET) {
            val window = Bitmap.createBitmap(scaled, 0, offset, SCREEN_WIDTH, VIEWPORT)
            val boxes = detector.detectRaw(window, OnnxBubbleDetector.SCORE_FLOOR)
                .filter { it.label == OnnxBubbleDetector.LABEL_TEXT_IN_BUBBLE }
                .map { it.box }
            window.recycle()

            println("SCROLLMATCH")
            println("SCROLLMATCH offset $offset: ${boxes.size} balloons")

            previous?.let { before ->
                // What the scroll actually was, in screen pixels.
                val scrolled = offset - previousOffset
                report(before, boxes, scrolled)
            }

            previous = boxes
            previousOffset = offset
            offset += STEP
        }

        detector.release()
        scaled.recycle()
    }

    /**
     * How many of the previous screen's balloons can be found on this one.
     *
     * Two ways of asking, because they answer different questions:
     *
     * - **known shift** — assume the scroll distance is known exactly (it would
     *   be, from the accessibility event or from the image). This measures the
     *   box jitter alone.
     * - **recovered shift** — take the most common vertical difference between
     *   plausible pairs and use that. This is what the pipeline would have to
     *   do, and it also says whether the shift is recoverable at all.
     */
    private fun report(before: List<TextBounds>, after: List<TextBounds>, scrolled: Int) {
        val expected = before.filter { it.top - scrolled >= 0 }
        println(
            "SCROLLMATCH   scrolled $scrolled px; ${expected.size} of ${before.size} " +
                "should still be on screen",
        )

        for (tolerance in TOLERANCES) {
            val matched = expected.count { old ->
                after.any { new -> agrees(old, new, scrolled, tolerance) }
            }
            print("SCROLLMATCH   known shift, ±${tolerance}px: $matched/${expected.size}")
            println()
        }

        // And what the shipped code makes of the same two frames. Measured
        // through `ScrolledBalloons` rather than a copy of it, so this cannot
        // drift into reporting on an algorithm nothing runs.
        val recovered = ScrolledBalloons.shiftBetween(before, after)
        if (recovered == null) {
            println("SCROLLMATCH   ScrolledBalloons: no consistent shift — everything re-read")
            return
        }
        val reused = expected.count { old ->
            after.any { new -> ScrolledBalloons.isSame(old, new, recovered) }
        }
        println(
            "SCROLLMATCH   ScrolledBalloons: shift $recovered (actual $scrolled, " +
                "off by ${abs(recovered - scrolled)}), reused $reused/${expected.size}",
        )
    }

    /**
     * Whether [new] is [old] moved up by [shift].
     *
     * Size is part of it: a webtoon scrolls vertically, so a balloon keeps its
     * width and height, and two balloons of different sizes at similar heights
     * are not the same balloon however close they land.
     */
    private fun agrees(old: TextBounds, new: TextBounds, shift: Int, tolerance: Int): Boolean =
        abs(new.left - old.left) <= tolerance &&
            abs((new.top + shift) - old.top) <= tolerance &&
            abs(new.width - old.width) <= tolerance &&
            abs(new.height - old.height) <= tolerance

    private companion object {
        const val WEBTOON = "jap-mag-09.jpg"

        /** The emulator's portrait width, so the model sees production sizes. */
        const val SCREEN_WIDTH = 1080

        /** Roughly what a browser leaves for content on a 1920-tall screen. */
        const val VIEWPORT = 1700

        /** A swipe of the size measured on the device. */
        const val STEP = 700

        /** Enough scrolls to see whether it holds up, without a long run. */
        const val MAX_OFFSET = 700 * 5

        val TOLERANCES = listOf(4, 8, 16, 32)
    }
}
