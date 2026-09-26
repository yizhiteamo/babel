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
 * What each balloon on a page actually says, in the order a reader takes them.
 *
 * The cross-balloon problem needs exact strings: a sentence split across
 * balloons has to be experimented on with the text the engine really receives,
 * not with a transcription of the artwork. Page 07's five balloons were read
 * off an annotated image and that reading is a guess — manga-ocr may see
 * something else, and the something else is what DeepL is given.
 *
 * Its output feeds `BalloonContextExperimentTest` in `:data:translation`, which
 * cannot run this itself: capture does not depend on the translation module,
 * and that separation is deliberate (`CLAUDE.md`).
 *
 * ## Reading order
 *
 * The pipeline does not compute one. `DetectingPageReader` hands balloons over
 * in whatever order the model emits, which is fine when each balloon is its own
 * sentence and is exactly what breaks when one sentence spans several. So this
 * sorts them the way manga is read — **right to left, top to bottom**, with a
 * row tolerance so two balloons at roughly the same height are taken as a row.
 * If grouping is built, that order is the first thing it needs.
 *
 * Recognised text **is** printed, as `MangaMaterialEvaluationTest` already does
 * and for the same reason: the strings are the measurement. Safe here and
 * nowhere else — the material is supplied locally and never committed, and this
 * is a test rather than the app (`docs/systems/privacy.md`).
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * # manga-ocr lives in the app's files dir; the test package needs its own copy
 * # Copy the three model files across with `adb shell cp` — Kotlin nests
 * # block comments, so a literal glob cannot be written inside one.
 * #   from /sdcard/Android/data/com.babel/files/models
 * #   to   /sdcard/Android/data/com.babel.platform.capture.test/files/models
 * adb shell am instrument -w -e class \
 *   com.babel.platform.capture.PageTextDumpTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep PAGETEXT
 * ```
 */
@RunWith(AndroidJUnit4::class)
class PageTextDumpTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    /** What the emulator shows a page at, so the recogniser sees what it sees. */
    private val SCREEN_WIDTH = 1080

    /** Two balloons within this fraction of the page's height are one row. */
    private val rowTolerance = 0.08f

    @Test
    fun dumpEachPageInReadingOrder() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }
            .orEmpty()

        if (pages.isEmpty()) {
            println("PAGETEXT skipped: needs comic-sample pushed")
            return@runBlocking
        }

        val manga = MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp)
        println("PAGETEXT manga-ocr available: ${manga.isAvailable}")
        if (!manga.isAvailable) {
            println("PAGETEXT copy the models into this package's files/models first")
            return@runBlocking
        }

        val general = MlKitTextRecognizer(BabelLogger.NoOp)
        val recognizer = BubbleRecognizer(manga, general)
        val reader = DetectingPageReader(
            detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp),
            recognizer = recognizer,
            fallback = GroupingPageReader(general),
            logger = BabelLogger.NoOp,
        )

        for (file in pages) {
            val full = BitmapFactory.decodeFile(file.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue

            // At the size the device actually reads it. A page is shown in a
            // browser on a 1080-wide screen, so what reaches the recogniser is
            // a **scaled-down** picture, and a recogniser reading smaller type
            // breaks lines differently. Reading the file at its own size
            // measures something the pipeline never sees, which is how a fix
            // came to look right in the harness and wrong on the device.
            val page = if (full.width > SCREEN_WIDTH) {
                val height = full.height * SCREEN_WIDTH / full.width
                Bitmap.createScaledBitmap(full, SCREEN_WIDTH, height, true)
                    .also { if (it !== full) full.recycle() }
            } else {
                full
            }

            val regions = mutableListOf<TextRegion>()
            reader.read(page) { regions += it; true }

            println("PAGETEXT")
            println("PAGETEXT === ${file.name} (${page.width}x${page.height}) ===")
            for ((index, region) in regions.sortedWith(readingOrder(page)).withIndex()) {
                val kind = if (region.enclosure == null) "art " else "bubble"
                println(
                    "PAGETEXT   %2d %s %-22s %s".format(
                        index + 1,
                        kind,
                        "@${region.bounds.left},${region.bounds.top} " +
                            "${region.bounds.width}x${region.bounds.height}",
                        region.text,
                    ),
                )
                // The lines before they are joined. `TextRegion.text` runs them
                // together with no separator, which is right for a vertical
                // Japanese column and glues words together in a horizontal
                // Latin one — `who` + `can't` becoming `whocan't`. Whether the
                // seams in a page's text really are line boundaries is only
                // visible here.
                if (region.lines.size > 1) {
                    region.lines.forEachIndexed { line, recognised ->
                        println("PAGETEXT        line ${line + 1}: ${recognised.text}")
                    }
                }
            }
            page.recycle()
        }

        reader.release()
    }

    /**
     * Right to left, top to bottom, which is how a Japanese page is read.
     *
     * Rows first, because a balloon slightly lower but far to the right still
     * comes first. Without the tolerance a two-pixel difference in box tops
     * reorders a row, and the order is the whole point here.
     */
    private fun readingOrder(page: Bitmap): Comparator<TextRegion> {
        val band = (page.height * rowTolerance).toInt().coerceAtLeast(1)
        return compareBy<TextRegion> { it.bounds.top / band }
            .thenByDescending { it.bounds.right }
    }
}
