package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
 * Whether one balloon reads the same from different scroll positions.
 *
 * Asked because the same title was seen translated two different ways. The
 * translation cache is keyed on the text, so identical text cannot translate
 * differently twice; if the translations differ, the text did. This reads a
 * column of three pages through a phone-sized window at many offsets, each
 * time with a fresh reader so nothing is reused, and prints what every region
 * said, in page coordinates so the same balloon can be lined up across reads.
 *
 * Recognised text is printed, as `PageTextDumpTest` does and for the same
 * reason: the strings are the measurement, the material is local and never
 * committed, and this is a test rather than the app (`docs/systems/privacy.md`).
 * Results: `docs/milestones/v2.md`, *同一气泡每次读得不一样*.
 *
 * Prints; asserts nothing.
 *
 * ```
 * # jap-mag-14/15/16 into the test package's files/comic-sample, models as for PageTextDumpTest
 * adb shell am instrument -w -e class  *   com.babel.platform.capture.ReadingVarianceMeasurementTest  *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep VARIANCE
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ReadingVarianceMeasurementTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun readTheSameColumnFromManyOffsets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "comic-sample")
        val names = listOf("jap-mag-14.jpg", "jap-mag-15.jpg", "jap-mag-16.jpg")
        if (names.any { !File(dir, it).exists() }) {
            println("VARIANCE skipped")
            return@runBlocking
        }
        val parts = names.map { name ->
            val full = BitmapFactory.decodeFile(File(dir, name).absolutePath)
            Bitmap.createScaledBitmap(full, WIDTH, full.height * WIDTH / full.width, true)
        }
        val column = Bitmap.createBitmap(WIDTH, parts.sumOf { it.height }, Bitmap.Config.ARGB_8888)
        Canvas(column).apply {
            var y = 0f
            for (part in parts) { drawBitmap(part, 0f, y, null); y += part.height }
        }
        println("VARIANCE column ${column.height} starts ${parts.runningFold(0) { a, p -> a + p.height }}")

        val manga = MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp)
        val general = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.JAPANESE)
        val korean = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.KOREAN)
        val recognizer = BubbleRecognizer(manga, general, korean)
        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)

        var offset = FIRST
        while (offset <= LAST) {
            val window = Bitmap.createBitmap(column, 0, offset, WIDTH, HEIGHT)
            // A fresh reader each time: no memory, so every balloon is read anew.
            val reader = DetectingPageReader(detector, recognizer, GroupingPageReader(general), BabelLogger.NoOp)
            reader.read(window) { region ->
                val b = region.bounds
                println("VARIANCE $offset ${b.left},${b.top + offset},${b.width},${b.height}\t${region.text}")
                true
            }
            window.recycle()
            offset += STEP
        }
        println("VARIANCE done")
        recognizer.release()
        detector.release()
    }

    private companion object {
        /** Phone width, and Edge's content height on that phone. */
        const val WIDTH = 1440
        const val HEIGHT = 3062

        /** From the first page's balloons to the end of the column. */
        const val FIRST = 700
        const val LAST = 3847
        const val STEP = 37
    }
}
