package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether the balloon detector finds our own translations.
 *
 * Observed on a device: turning from one comic page to another, the scan that
 * followed reported **six** balloons on a page that has four, and the two extra
 * ones carried the previous page's Chinese — which then stayed on screen until
 * something forced a re-scan.
 *
 * Two explanations fit that. The first, that the change detector missed the
 * page turn, was measured and ruled out: `FrameSignatureMeasurementTest` puts
 * the closest pair of these pages at 0.345 against a 0.30 threshold, so every
 * turn is seen.
 *
 * The second is this one. A frame is taken from the live screen, so it contains
 * whatever Babel has already drawn — and a translation is a white rounded box
 * with lettering in it, which is exactly what a comic balloon detector is
 * trained to find. When the page changes, the read happens while the *previous*
 * page's translations are still up, so they are there to be found.
 *
 * `FrameChangeDetector` is documented as what stops the OCR path reading its
 * own output, and it does — for an unchanged page. It cannot help on the one
 * frame where the page has just changed, which is the only frame that gets read.
 *
 * Prints and asserts: the count is the whole point, and a detector that stops
 * finding drawn boxes would make the fix unnecessary.
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * ./gradlew :platform:capture:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=
 *     com.babel.platform.capture.DetectorReadsOwnOverlaysTest
 * adb logcat -d | grep OWNOVERLAY
 * ```
 */
@RunWith(AndroidJUnit4::class)
class DetectorReadsOwnOverlaysTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun aDrawnTranslationLooksLikeABalloonToTheDetector() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val page = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            ?.firstOrNull()

        if (page == null) {
            println("OWNOVERLAY skipped: needs comic-sample pushed")
            return@runBlocking
        }

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        val bare = BitmapFactory.decodeFile(page.absolutePath)
            ?.copy(Bitmap.Config.ARGB_8888, false) ?: return@runBlocking

        val before = detector.detect(bare).size
        println("OWNOVERLAY ${page.name}: $before balloons as drawn")

        // Two translations from a previous page, in places its balloons were
        // but this page's are not — the bottom strip, which on this page is
        // artwork. Drawn the way the renderer draws: an opaque light box with
        // dark lettering, rounded like a balloon.
        val withOurs = bare.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(withOurs)
        drawTranslation(canvas, withOurs, 0.06f, 0.70f, 0.26f, 0.16f, "上一页的译文")
        drawTranslation(canvas, withOurs, 0.66f, 0.72f, 0.28f, 0.14f, "另一条旧译文")

        val after = detector.detect(withOurs).size
        println("OWNOVERLAY ${page.name}: $after balloons with two of ours drawn on")
        println("OWNOVERLAY detector attributed ${after - before} extra balloons to our own output")

        bare.recycle()
        withOurs.recycle()
        detector.release()

        // Not an exact count: what matters is that drawing translations adds
        // balloons the page does not have. If this ever fails because the
        // detector learned to ignore them, the exclusion this justifies can go.
        kotlin.test.assertTrue(
            after > before,
            "expected our own translations to be detected as balloons, " +
                "got $before before and $after after",
        )
    }

    private fun drawTranslation(
        canvas: Canvas,
        frame: Bitmap,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        text: String,
    ) {
        val rect = RectF(
            x * frame.width,
            y * frame.height,
            (x + w) * frame.width,
            (y + h) * frame.height,
        )
        val box = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        canvas.drawRoundRect(rect, rect.width() * 0.12f, rect.width() * 0.12f, box)

        val ink = Paint().apply {
            color = Color.BLACK
            isAntiAlias = true
            textSize = rect.height() * 0.30f
        }
        canvas.drawText(text, rect.left + rect.width() * 0.08f, rect.centerY(), ink)
    }
}
