package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the on-device general recogniser makes of game screenshots, and how
 * long it takes — V3's second spike (`docs/milestones/v3.md`).
 *
 * Game text is horizontal print on panels, not hand-lettered vertical columns,
 * so the manga recogniser is not the candidate; ML Kit's Japanese model reads
 * whole frames and returns every line with its box, which is also where the
 * "do not translate" rules get their input.
 *
 * Each screenshot is scaled to the phone's landscape size first, because that
 * is what a window screenshot hands over (3200x1440, measured in spike 1), and
 * a recogniser reading smaller type behaves differently.
 *
 * Recognised text is printed: the strings are the measurement, the material is
 * local and never committed, and this is a test rather than the app
 * (`docs/systems/privacy.md`).
 *
 * ```
 * adb shell mkdir -p /sdcard/Android/data/com.babel.platform.capture.test/files/game-sample
 * adb push material-sample/game/. /sdcard/Android/data/com.babel.platform.capture.test/files/game-sample/
 * adb shell am instrument -w -e class com.babel.platform.capture.GameOcrMeasurementTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep GAMEOCR
 * ```
 */
@RunWith(AndroidJUnit4::class)
class GameOcrMeasurementTest {

    @Test
    fun readEveryScreenshot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shots = File(context.getExternalFilesDir(null), "game-sample")
            .listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedBy { it.name }
            .orEmpty()
        if (shots.isEmpty()) {
            println("GAMEOCR skipped: needs game-sample pushed")
            return@runBlocking
        }

        val recognizer = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.JAPANESE)
        for (file in shots) {
            val raw = BitmapFactory.decodeFile(file.absolutePath)
            val frame = Bitmap.createScaledBitmap(raw, WIDTH, raw.height * WIDTH / raw.width, true)

            // The first call loads the model; timed runs come after it.
            recognizer.recognize(frame)
            val times = LongArray(RUNS)
            var lines = emptyList<com.babel.domain.vision.RecognizedLine>()
            for (run in 0 until RUNS) {
                val started = System.nanoTime()
                lines = recognizer.recognize(frame)
                times[run] = (System.nanoTime() - started) / 1_000_000
            }
            println("GAMEOCR === ${file.name} ${frame.width}x${frame.height} ms=${times.joinToString(",")} lines=${lines.size}")
            for (line in lines) {
                val b = line.bounds
                println("GAMEOCR   ${b.left},${b.top},${b.width},${b.height}\t${line.text}")
            }
        }
        println("GAMEOCR done")
    }

    private companion object {
        /** The phone's long side, which a landscape window screenshot spans. */
        const val WIDTH = 3200
        const val RUNS = 3
    }
}
