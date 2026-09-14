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
 * Recognised text **is** printed, by `reportPerBubble` alone. That is a
 * deliberate exception, not an oversight: the page-wide character overlap this
 * used to rely on cannot tell a correctly read bubble from two bubbles spliced
 * together, and that distinction is the whole question. Seeing the strings is
 * the only way to answer it.
 *
 * It is safe here and nowhere else: the material is supplied locally, never
 * committed, and this is a test rather than the app. The app's own diagnostics
 * still carry counts and sizes only (`docs/systems/privacy.md`).
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
    private val groundTruth = BubbleScoring.groundTruth

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
        val offCentre = mutableListOf<Double>()

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

                // How far the translation will be drawn from the text it
                // replaces. The renderer centres in the grown box, but the text
                // sits wherever it sits inside it — nothing measured this
                // before, and "looks well covered" in a screenshot was the only
                // check the alignment ever had.
                val drift = drift(region.bounds, grown)
                offCentre += drift
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
                        " drift=${percent(drift)}" +
                        " chars=${region.text.length} orientation=${region.orientation}",
                )
            }

            groundTruth[page.name]?.let { expected ->
                reportAccuracy(page.name, expected, regions.joinToString("") { it.text })
                reportPerBubble(page.name, expected, regions.map { it.text })
            }

            bitmap.recycle()
        }

        println("MANGA_EVAL -- summary --")
        println("MANGA_EVAL enclosed(bubbles)=$enclosed open(no enclosure)=$open")
        report("enclosed", enclosedFlatness)
        report("open", openFlatness)
        report("drift", offCentre)
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

    /**
     * Distance between the text's centre and its grown box's centre, as a
     * fraction of the text box's size.
     *
     * 0 means the translation lands exactly where the original was. Anything
     * approaching 0.5 means it is drawn a whole text-width away — which is what
     * "the bubbles are misaligned" looks like from the outside.
     */
    private fun drift(text: TextBounds, grown: TextBounds): Double {
        val dx = ((text.left + text.right) - (grown.left + grown.right)) / 2.0
        val dy = ((text.top + text.bottom) - (grown.top + grown.bottom)) / 2.0
        val scale = maxOf(text.width, text.height).coerceAtLeast(1)
        return kotlin.math.hypot(dx, dy) / scale
    }

    /**
     * Each recognised region against the transcribed bubble it resembles most.
     *
     * The page-wide character overlap above flatters badly: it asks only
     * whether a character appears *somewhere* on the page, so a region holding
     * two bubbles' text spliced together scores well while translating to
     * nonsense. This asks the question that matters — is this region one
     * bubble, and in the right order.
     *
     * It prints recognised text, which the app itself never does. That is the
     * point of it being a test over material supplied locally and never
     * committed.
     */
    private fun reportPerBubble(page: String, expected: List<String>, regions: List<String>) {
        regions.forEach { region ->
            val clean = region.filterNot(Char::isWhitespace)
            val best = expected.maxByOrNull { BubbleScoring.similarity(clean, it) }
            val score = best?.let { BubbleScoring.similarity(clean, it) }
            println("MANGA_EVAL   bubble $page ${percent(score ?: 0.0)} got=\"$clean\" near=\"${best.orEmpty()}\"")
        }
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
