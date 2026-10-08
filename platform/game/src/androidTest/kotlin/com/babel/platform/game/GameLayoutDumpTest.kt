package com.babel.platform.game

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How ML Kit structures a game screenshot — blocks, lines and the elements in
 * each line, with boxes — for designing how speaker lines are split and
 * wrapped dialogue joined (`docs/milestones/v3.md`).
 *
 * Text is printed: the strings are the measurement, the material is local and
 * never committed, and this is a test (`docs/systems/privacy.md`).
 *
 * ```
 * adb push <shot>.png /sdcard/Android/data/com.babel.platform.game.test/files/game-sample/
 * adb shell am instrument -w -e class com.babel.platform.game.GameLayoutDumpTest \
 *   com.babel.platform.game.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep GAMELAYOUT
 * ```
 */
@RunWith(AndroidJUnit4::class)
class GameLayoutDumpTest {

    @Test
    fun dump() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shots = File(context.getExternalFilesDir(null), "game-sample").listFiles()?.sortedBy { it.name }.orEmpty()
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        for (file in shots) {
            val frame = BitmapFactory.decodeFile(file.absolutePath) ?: continue
            val result = recognizer.process(InputImage.fromBitmap(frame, 0)).await()
            println("GAMELAYOUT === ${file.name} ${frame.width}x${frame.height}")
            result.textBlocks.forEachIndexed { b, block ->
                println("GAMELAYOUT block $b ${block.boundingBox?.toShortString()}")
                for (line in block.lines) {
                    println("GAMELAYOUT   line ${line.boundingBox?.toShortString()} conf=${"%.2f".format(line.confidence)}\t${line.text}")
                    for (e in line.elements) {
                        println("GAMELAYOUT     el ${e.boundingBox?.toShortString()}\t${e.text}")
                    }
                }
            }
            frame.recycle()
        }
        println("GAMELAYOUT done")
    }
}
