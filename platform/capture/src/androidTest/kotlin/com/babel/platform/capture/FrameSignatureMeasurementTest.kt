package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.domain.vision.FrameChangeDetector
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Why a page turn inside one app can go unnoticed.
 *
 * Observed on a device: navigating from one comic page to another in the
 * browser left the previous page's translations on screen for two minutes,
 * with the scan loop reporting `skipped: the same page as last read` the whole
 * time. A small scroll fixed it at once.
 *
 * `FrameChangeDetector` asks for **30%** of cells to differ before it calls a
 * frame a new page. Its own comment says that number was "sized from the two
 * things being told apart, not tuned" — translations appearing (about a tenth
 * of the screen) versus a page turn (nearly all of it). A *different page that
 * looks similar* was not among them.
 *
 * Three things push the measured difference below the threshold, and this
 * measures each so a new threshold can be chosen from numbers rather than
 * guessed:
 *
 * 1. **The signature covers the whole frame.** `CaptureTextSource` computes it
 *    before cropping to the content area, so the browser's chrome — identical
 *    between two pages of the same site — sits in the denominator.
 * 2. **Our own translations are in the frame.** They cover the very regions
 *    that would otherwise differ, and they are in *both* frames being compared:
 *    the previous page's overlays are still up when the next page is read.
 * 3. Two pages of one comic share a lot of sky and character.
 *
 * Prints; asserts nothing. Like `BubbleSignatureTest`, this chooses constants
 * rather than guarding them.
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * ./gradlew :platform:capture:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=
 *     com.babel.platform.capture.FrameSignatureMeasurementTest
 * adb logcat -d | grep SIGDIFF
 * ```
 */
@RunWith(AndroidJUnit4::class)
class FrameSignatureMeasurementTest {

    /**
     * Fraction of the frame the browser's chrome occupies, and the balloons
     * measured on it — taken from `dumpsys window windows` during the failure,
     * on a 1920x1080 landscape frame, rather than invented:
     *
     * ```
     * (1529,255)(328x552)  (16,696)(233x347)
     * (205,265)(150x271)   (220,622)(105x211)
     * ```
     */
    private val chromeHeightFraction = 205f / 1080f

    private val balloonFractions = listOf(
        RectF4(1529f / 1920, 255f / 1080, 328f / 1920, 552f / 1080),
        RectF4(16f / 1920, 696f / 1080, 233f / 1920, 347f / 1080),
        RectF4(205f / 1920, 265f / 1080, 150f / 1920, 271f / 1080),
        RectF4(220f / 1920, 622f / 1080, 105f / 1920, 211f / 1080),
    )

    private data class RectF4(val x: Float, val y: Float, val w: Float, val h: Float)

    /** The production constant, mirrored because `differingFraction` is private. */
    private val cellTolerance = 12

    @Test
    fun measureWhatSeparatesAPageTurnFromATranslationAppearing() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (pages.size < 2) {
            println("SIGDIFF skipped: needs comic-sample pushed (found ${pages.size} pages)")
            return
        }

        val loaded = pages.mapNotNull { file ->
            BitmapFactory.decodeFile(file.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false)
                ?.let { file.name to it }
        }

        println("SIGDIFF threshold in force: CHANGED_FRACTION=0.30, CELL_TOLERANCE=$cellTolerance")
        println("SIGDIFF grid is 32x18 = 576 cells")

        // --- the number the threshold must stay ABOVE ---------------------
        // The same page with our translations drawn on it. Dropping below this
        // puts the detector into the loop it exists to prevent: an overlay
        // appearing reads as a new page, ids change, translations rebuild.
        println("SIGDIFF")
        println("SIGDIFF === same page, translations appear (threshold floor) ===")
        var floorWorst = 0.0
        for ((name, page) in loaded) {
            val bare = frameOf(page, withBalloons = false)
            val covered = frameOf(page, withBalloons = true)
            val fraction = differing(signature(bare), signature(covered))
            floorWorst = maxOf(floorWorst, fraction)
            println("SIGDIFF   %-16s %.3f".format(name, fraction))
            bare.recycle(); covered.recycle()
        }

        // --- the numbers the threshold must stay BELOW --------------------
        // A different page, compared the way the detector actually compares:
        // the previous page's overlays are still up when the next is read, so
        // both frames carry the same blocks in the same places.
        println("SIGDIFF")
        println("SIGDIFF === different page, three scopes ===")
        println("SIGDIFF   pair                content  +chrome  +chrome+overlays  verdict")

        var contentWorst = 1.0
        var fullWorst = 1.0
        var realisticWorst = 1.0

        for (i in loaded.indices) {
            for (j in loaded.indices) {
                if (i == j) continue
                val (aName, a) = loaded[i]
                val (bName, b) = loaded[j]

                val contentOnly = differing(signature(a), signature(b))

                val aFrame = frameOf(a, withBalloons = false)
                val bFrame = frameOf(b, withBalloons = false)
                val withChrome = differing(signature(aFrame), signature(bFrame))

                val aCovered = frameOf(a, withBalloons = true)
                val bCovered = frameOf(b, withBalloons = true)
                val realistic = differing(signature(aCovered), signature(bCovered))
                // What production would decide today.
                val verdict = FrameChangeDetector.shouldRecognize(
                    signature(aCovered),
                    signature(bCovered),
                )

                contentWorst = minOf(contentWorst, contentOnly)
                fullWorst = minOf(fullWorst, withChrome)
                realisticWorst = minOf(realisticWorst, realistic)

                println(
                    "SIGDIFF   %-8s->%-8s %7.3f %8.3f %17.3f  %s".format(
                        aName.removeSuffix(".jpg"),
                        bName.removeSuffix(".jpg"),
                        contentOnly,
                        withChrome,
                        realistic,
                        if (verdict) "re-reads" else "SKIPS",
                    ),
                )

                aFrame.recycle(); bFrame.recycle(); aCovered.recycle(); bCovered.recycle()
            }
        }

        println("SIGDIFF")
        println("SIGDIFF === the gap ===")
        println("SIGDIFF   worst case for an overlay appearing (floor): %.3f".format(floorWorst))
        println("SIGDIFF   closest pair, content only:                  %.3f".format(contentWorst))
        println("SIGDIFF   closest pair, whole frame:                   %.3f".format(fullWorst))
        println("SIGDIFF   closest pair, frame + overlays (real):       %.3f".format(realisticWorst))
        println(
            "SIGDIFF   a threshold has to sit above %.3f and below %.3f".format(
                floorWorst,
                realisticWorst,
            ),
        )
        println(
            "SIGDIFF   gap on the scope used today: %.3f".format(realisticWorst - floorWorst),
        )

        loaded.forEach { (_, bitmap) -> bitmap.recycle() }
    }

    /**
     * A page as it appears on screen: chrome above it, and optionally our own
     * translations drawn over the balloons.
     *
     * The chrome is a flat block rather than a screenshot of a real toolbar —
     * what matters is that it is *identical between the two frames*, which is
     * what makes it dilute the difference.
     */
    private fun frameOf(page: Bitmap, withBalloons: Boolean): Bitmap {
        val width = page.width
        val height = (page.height / (1f - chromeHeightFraction)).toInt()
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val paint = Paint()

        paint.color = Color.rgb(32, 33, 36)
        canvas.drawRect(0f, 0f, width.toFloat(), height * chromeHeightFraction, paint)
        canvas.drawBitmap(page, 0f, height * chromeHeightFraction, null)

        if (withBalloons) {
            paint.color = Color.WHITE
            for (balloon in balloonFractions) {
                canvas.drawRect(
                    Rect(
                        (balloon.x * width).toInt(),
                        (balloon.y * height).toInt(),
                        ((balloon.x + balloon.w) * width).toInt(),
                        ((balloon.y + balloon.h) * height).toInt(),
                    ),
                    paint,
                )
            }
        }
        return frame
    }

    private fun signature(frame: Bitmap): IntArray = FrameSignature.of(frame)

    /** The same arithmetic `FrameChangeDetector` uses, which does not expose it. */
    private fun differing(previous: IntArray, current: IntArray): Double {
        if (previous.size != current.size || current.isEmpty()) return 1.0
        var differing = 0
        for (index in current.indices) {
            if (kotlin.math.abs(current[index] - previous[index]) > cellTolerance) differing++
        }
        return differing.toDouble() / current.size
    }
}
