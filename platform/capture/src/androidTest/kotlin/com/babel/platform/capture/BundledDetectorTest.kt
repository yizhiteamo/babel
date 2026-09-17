package com.babel.platform.capture

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import java.io.File
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The detector ships inside the package now (ADR 011), and this is what says so
 * on a device rather than in a build log.
 *
 * Needs no comic pages, deliberately: what it checks is that a session loads,
 * not what the model finds. A blank bitmap yields no balloons either way, so
 * the assertion is on [TextDetector.isAvailable] *after* a detection — which is
 * the only moment a failed load becomes visible, since availability is assumed
 * until a load has been tried.
 *
 * To exercise the bundled copy specifically, move any pushed override aside
 * first — a pushed file wins, by design:
 *
 * ```
 * adb shell mv /sdcard/Android/data/com.babel/files/models/detector.onnx{,.bak}
 * ./gradlew :platform:capture:connectedDebugAndroidTest --tests "*BundledDetectorTest"
 * adb shell mv /sdcard/Android/data/com.babel/files/models/detector.onnx{.bak,}
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BundledDetectorTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun theDetectorLoadsWithNothingPushedToTheDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pushed = File(File(context.getExternalFilesDir(null), "models"), "detector.onnx")
        println("BUNDLED pushed override present: ${pushed.exists()}")

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        val blank = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)

        try {
            detector.detect(blank)
            // Not "it found something" — it cannot, on a blank page. A failed
            // load latches `failed`, and that is what this catches.
            assertTrue(detector.isAvailable, "the detector failed to load")
        } finally {
            blank.recycle()
            detector.release()
        }
    }

    /** The attribution has to be present too, or shipping the model is not ok. */
    @Test
    fun theLicenceAndAttributionShipBesideIt() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets

        val notice = assets.open("licenses/NOTICE.txt").use { it.readBytes().decodeToString() }
        assertTrue(
            notice.contains("comic-text-and-bubble-detector"),
            "the notice does not name the bundled model",
        )
        assertTrue(notice.contains("Apache License 2.0"), "the notice does not name the licence")

        val licence = assets.open("licenses/apache-2.0.txt").use { it.readBytes().decodeToString() }
        assertTrue(licence.contains("Apache License"), "the licence text is not the licence")
    }
}
