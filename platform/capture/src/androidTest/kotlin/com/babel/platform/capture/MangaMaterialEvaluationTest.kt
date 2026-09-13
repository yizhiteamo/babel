package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.model.TextBounds
import com.babel.domain.vision.BubbleBounds
import com.babel.domain.vision.ColorAnalysis
import com.babel.domain.vision.TextRegionGrouper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures V2 against real manga, which is the one thing its acceptance record
 * admits it never did.
 *
 * `docs/milestones/v2.md` sets three criteria and one rule: a result is only a
 * reason to change course if it names which of them failed. So this prints
 * numbers and asserts almost nothing — it is an instrument, not a gate.
 *
 * It runs the **real** components rather than an approximation of them:
 * [MlKitTextRecognizer], [TextRegionGrouper], [ColorAnalysis] and
 * [BubbleBounds] are exactly what the pipeline uses. A harness that
 * reimplemented any of them would measure the harness.
 *
 * ## Material
 *
 * Pushed to the device at run time and never committed — it is copyrighted work
 * belonging to someone else. Without it the test skips and says so, so the
 * repository stays runnable by anyone.
 *
 * Recognised text is never printed. It is screen content, and
 * `docs/systems/privacy.md` does not stop applying because this is a test. What
 * is printed is counts, sizes and percentages, plus the expected strings, which
 * are our own transcription rather than anything the device read.
 */
@RunWith(AndroidJUnit4::class)
class MangaMaterialEvaluationTest {

    /**
     * Hand-transcribed bubble text, speech bubbles only.
     *
     * Sound effects drawn onto the art are deliberately absent: they are out of
     * V2 scope, so a page should not be marked down for text the design intends
     * to leave alone.
     *
     * **This is my own reading of the pages, so it is itself a source of
     * error.** A character transcribed wrongly here counts against OCR that
     * read it rightly.
     */
    private val groundTruth = mapOf(
        "jap-mag-01.jpg" to listOf(
            "先生も汗拭きシート使いますか",
            "いいの",
            "はいいくらでも使ってください",
            "そっちは私の使用済み",
            "先生先生",
        ),
        "jap-mag-04.jpg" to listOf(
            "スーパーアルバイターの資格次が最終試験この本も最終ですッ",
            "どんなことが書かれて",
            "仕事中突然視界が高くなったり増えたり手足色声が変化して",
            "周囲が泣いたり騒いだり逃げ出した時店主の言葉や誘導は無視して",
            "目を閉じて",
            "絶対に動かないこと",
            "あんまりわかんないケドッ",
            "がんばりまーす",
        ),
    )

    @Test
    fun measureRealPages() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), MATERIAL_DIR)

        val pages = dir.listFiles { file -> file.extension.lowercase() in IMAGE_TYPES }
            ?.sortedBy { it.name }
            .orEmpty()

        if (pages.isEmpty()) {
            println("MANGA_EVAL skipped: no material in ${dir.absolutePath}")
            println("MANGA_EVAL push real pages there to run this; they are never committed.")
            return@runBlocking
        }

        val recognizer = MlKitTextRecognizer(BabelLogger.NoOp)
        val grouper = TextRegionGrouper()

        var enclosed = 0
        var open = 0
        val enclosedFlatness = mutableListOf<Double>()
        val openFlatness = mutableListOf<Double>()

        for (page in pages) {
            val bitmap = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false)
            if (bitmap == null) {
                println("MANGA_EVAL ${page.name}: could not decode")
                continue
            }

            val lines = recognizer.recognize(bitmap)
            val regions = grouper.group(lines)
            val frame = FrameSampler.frameBounds(bitmap)

            println(
                "MANGA_EVAL page=${page.name} ${bitmap.width}x${bitmap.height}" +
                    " lines=${lines.size} regions=${regions.size}",
            )

            regions.forEach { region ->
                val style = FrameSampler.sample(bitmap, region.bounds)
                val background = style.backgroundColor
                val grown = if (background == null) {
                    region.bounds
                } else {
                    BubbleBounds.expand(
                        start = region.bounds,
                        limit = frame,
                        isBackground = FrameSampler.backgroundTest(bitmap, background),
                    )
                }

                // Whether an enclosure was found is the interesting part: it is
                // how the renderer tells a speech bubble from a sound effect
                // painted onto the art.
                val isEnclosed = grown != region.bounds
                val flat = flatnessOf(bitmap, grown)
                if (isEnclosed) {
                    enclosed++
                    enclosedFlatness += flat
                } else {
                    open++
                    openFlatness += flat
                }

                println(
                    "MANGA_EVAL   region ${region.bounds.width}x${region.bounds.height}" +
                        " -> ${grown.width}x${grown.height}" +
                        " enclosed=$isEnclosed flatness=${percent(flat)}" +
                        " chars=${region.text.length} orientation=${region.orientation}",
                )
            }

            groundTruth[page.name]?.let { expected ->
                reportAccuracy(page.name, expected, regions.joinToString("") { it.text })
            }

            bitmap.recycle()
        }

        println("MANGA_EVAL -- summary --")
        println("MANGA_EVAL enclosed(bubbles)=$enclosed open(no enclosure)=$open")
        report("enclosed", enclosedFlatness)
        report("open", openFlatness)
    }

    /**
     * Character overlap, the same measure the OCR feasibility probe used, so
     * the 83% it found on synthetic lettering and whatever this finds on
     * published lettering are the same kind of number.
     */
    private fun reportAccuracy(page: String, expected: List<String>, recognised: String) {
        val found = recognised.filterNot(Char::isWhitespace).toSet()
        val ratios = expected.map { bubble ->
            val chars = bubble.filterNot(Char::isWhitespace).toSet()
            if (chars.isEmpty()) 1.0 else chars.count { it in found }.toDouble() / chars.size
        }
        expected.forEachIndexed { index, bubble ->
            println("MANGA_EVAL   ocr $page ${percent(ratios[index])}  $bubble")
        }
        println("MANGA_EVAL   ocr $page average=${percent(ratios.average())}")
    }

    private fun flatnessOf(bitmap: Bitmap, bounds: TextBounds): Double {
        val left = bounds.left.coerceIn(0, bitmap.width)
        val top = bounds.top.coerceIn(0, bitmap.height)
        val right = bounds.right.coerceIn(0, bitmap.width)
        val bottom = bounds.bottom.coerceIn(0, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return 0.0

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        return ColorAnalysis.flatness(pixels)
    }

    private fun report(label: String, values: List<Double>) {
        if (values.isEmpty()) {
            println("MANGA_EVAL $label: none")
            return
        }
        val sorted = values.sorted()
        val below = values.count { it < FLAT_ENOUGH }
        println(
            "MANGA_EVAL $label n=${values.size}" +
                " median=${percent(sorted[sorted.size / 2])}" +
                " min=${percent(sorted.first())} max=${percent(sorted.last())}" +
                " below${percent(FLAT_ENOUGH)}=$below (${percent(below.toDouble() / values.size)})",
        )
    }

    private fun percent(value: Double) = "${(value * 100).toInt()}%"

    private companion object {
        const val MATERIAL_DIR = "comic-sample"
        val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp")

        /**
         * Below this share of one colour, a background is not flat enough for
         * sampling to stand in for inpainting.
         */
        const val FLAT_ENOUGH = 0.6
    }
}
