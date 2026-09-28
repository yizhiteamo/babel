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

        val general = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.JAPANESE)
        val korean = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.KOREAN)
        val recognizer = BubbleRecognizer(manga, general, korean)
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
     * Every balloon the detector found, **including the ones that read as
     * nothing**.
     *
     * [dumpEachPageInReadingOrder] cannot answer this. It prints what
     * `DetectingPageReader.read` hands over, and a balloon that recognised to
     * nothing is dropped by `if (lines.isEmpty()) continue` *before* it reaches
     * the callback — so the very balloons the recall question is about are the
     * ones that tool cannot see. Measured on `kr-mag-01`: nine detected, six
     * delivered, and the three missing were known only by their sizes from a
     * log line (`docs/milestones/v2.md`).
     *
     * So this asks the detector and the recogniser directly and prints a row
     * per balloon, empty or not, making the page's arithmetic add up: detected
     * N, read M, and here is what each of the N−M actually contained.
     *
     * **It mirrors `DetectingPageReader.recognize` rather than calling it** —
     * that method is private, and the two-step it performs (the whole-page
     * reading first, the crop only as a fallback) is exactly what this is
     * measuring. Worth knowing they can drift apart; the step order is stated
     * here so a reader can check.
     */
    @Test
    fun dumpEveryDetectedBalloon() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            ?.sortedBy { it.name }
            .orEmpty()

        if (pages.isEmpty()) {
            println("BALLOONS skipped: needs comic-sample pushed")
            return@runBlocking
        }

        val manga = MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp)
        println("BALLOONS manga-ocr available: ${manga.isAvailable}")

        val general = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.JAPANESE)
        val korean = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.KOREAN)
        val recognizer = BubbleRecognizer(manga, general, korean)
        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)

        for (file in pages) {
            val full = BitmapFactory.decodeFile(file.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            val page = if (full.width > SCREEN_WIDTH) {
                val height = full.height * SCREEN_WIDTH / full.width
                Bitmap.createScaledBitmap(full, SCREEN_WIDTH, height, true)
                    .also { if (it !== full) full.recycle() }
            } else {
                full
            }

            // Same order as the pipeline: the page is judged before any balloon
            // is cropped, and the verdict decides both which engine reads and
            // which way the page is read.
            recognizer.startPage(page)
            val bubbles = detector.detect(page)

            println("BALLOONS")
            println(
                "BALLOONS === ${file.name} (${page.width}x${page.height}) " +
                    "detected=${bubbles.size} korean=${recognizer.pageIsKorean} ===",
            )

            var empty = 0
            for ((index, bubble) in bubbles.sortedWith(balloonOrder(page)).withIndex()) {
                val fromPage = recognizer.pageLinesIn(bubble.text)?.takeIf { it.isNotEmpty() }
                val lines = fromPage ?: run {
                    val crop = page.cropped(bubble.text)
                    if (crop == null) {
                        emptyList()
                    } else {
                        try {
                            recognizer.recognize(crop)
                        } finally {
                            crop.recycle()
                        }
                    }
                }
                if (lines.isEmpty()) empty++
                val box = bubble.text
                println(
                    "BALLOONS   %2d %-11s %-4s %-7s lines=%d %s".format(
                        index + 1,
                        "${box.width}x${box.height}@${box.left},${box.top}",
                        if (bubble.onArt) "art" else "bub",
                        if (fromPage != null) "page" else "crop",
                        lines.size,
                        if (lines.isEmpty()) "<empty>" else lines.joinToString(" | ") { it.text },
                    ),
                )
            }
            println("BALLOONS --- ${file.name}: ${bubbles.size} detected, $empty read as nothing")
            page.recycle()
        }

        recognizer.release()
        detector.release()
    }

    /** Same crop rule as `DetectingPageReader`, including its minimum side. */
    private fun Bitmap.cropped(bounds: com.babel.core.model.TextBounds): Bitmap? {
        val left = bounds.left.coerceIn(0, width)
        val top = bounds.top.coerceIn(0, height)
        val right = bounds.right.coerceIn(left, width)
        val bottom = bounds.bottom.coerceIn(top, height)
        if (right - left < 8 || bottom - top < 8) return null
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    /** [readingOrder] for detections rather than for regions. */
    private fun balloonOrder(page: Bitmap): Comparator<DetectedBubble> {
        val band = (page.height * rowTolerance).toInt().coerceAtLeast(1)
        return compareBy<DetectedBubble> { it.text.top / band }
            .thenByDescending { it.text.right }
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
