package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.model.TextBounds
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextRegionGrouper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures what preprocessing is worth before changing the recogniser.
 *
 * Two facts prompted this. The recogniser does no preprocessing at all — the
 * frame goes straight to ML Kit. And every OCR figure quoted up to now was
 * measured on the **original files**, while the app reads a screenshot: a
 * 1603x2048 page shown 1080 wide is two thirds the size, which drops glyphs
 * from roughly 40px to 27px. The numbers were flattering the app's real
 * situation.
 *
 * Nothing here changes the pipeline. It runs the same components over several
 * versions of the same page and reports how each scores, so the decision is
 * made on numbers rather than on which technique sounds most promising — two
 * changes made on reasoning alone have already had to be reverted.
 *
 * ## Reading the result
 *
 * A variant counts as better only if it improves **both** pages. The ground
 * truth is two pages and about thirteen bubbles, which is thin enough that a
 * gain on one page alone is more likely to be luck than progress.
 *
 * Recognised text is printed here, as in the other manga measurement, because
 * the question is which characters came out wrong. Material is supplied locally
 * and never committed; the app's own diagnostics still carry counts only.
 */
@RunWith(AndroidJUnit4::class)
class OcrPreprocessingExperimentTest {

    /** What the display does to a page: width fitted to a portrait screen. */
    private val screenWidth = 1080

    @Test
    fun comparePreprocessing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "comic-sample")

        val pages = BubbleScoring.groundTruth.keys
            .map { File(dir, it) }
            .filter { it.exists() }

        if (pages.isEmpty()) {
            println("OCR_EXP skipped: no material with ground truth in ${dir.absolutePath}")
            return@runBlocking
        }

        val recognizer = MlKitTextRecognizer(BabelLogger.NoOp)
        val grouper = TextRegionGrouper()
        val scores = LinkedHashMap<String, MutableList<Pair<String, Double>>>()

        for (page in pages) {
            val expected = BubbleScoring.groundTruth.getValue(page.name)
            val original = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            val displayed = original.scaledToWidth(screenWidth)

            // Named rather than stored as lambdas: each one is a suspending
            // call, and a map of suspending lambdas buys nothing here.
            val variants = listOf("original", "as-displayed", "upscaled-2x", "contrast", "two-pass")

            for (name in variants) {
                val regions = when (name) {
                    "original" -> recognise(recognizer, grouper, original)
                    "as-displayed" -> recognise(recognizer, grouper, displayed)
                    "upscaled-2x" -> {
                        val big = displayed.scaledToWidth(screenWidth * 2)
                        recognise(recognizer, grouper, big).also { big.recycle() }
                    }
                    "contrast" -> {
                        val boosted = displayed.highContrast()
                        recognise(recognizer, grouper, boosted).also { boosted.recycle() }
                    }
                    else -> twoPass(recognizer, grouper, displayed)
                }
                val score = BubbleScoring.pageScore(expected, regions)
                scores.getOrPut(name) { mutableListOf() } += page.name to score
                println("OCR_EXP ${page.name} $name ${BubbleScoring.percent(score)} regions=${regions.size}")
                regions.forEach { region ->
                    val best = expected.maxByOrNull { BubbleScoring.similarity(region, it) }
                    println(
                        "OCR_EXP     ${BubbleScoring.percent(BubbleScoring.similarity(region, best.orEmpty()))}" +
                            " got=\"$region\"",
                    )
                }
            }

            if (displayed !== original) displayed.recycle()
            original.recycle()
        }

        println("OCR_EXP -- summary (a variant must win on every page) --")
        scores.forEach { (name, perPage) ->
            val detail = perPage.joinToString(" ") { "${it.first.take(10)}=${BubbleScoring.percent(it.second)}" }
            println("OCR_EXP $name mean=${BubbleScoring.percent(perPage.map { it.second }.average())} $detail")
        }
    }

    private suspend fun recognise(
        recognizer: MlKitTextRecognizer,
        grouper: TextRegionGrouper,
        bitmap: Bitmap,
    ): List<String> = grouper.group(recognizer.recognize(bitmap)).map { it.text }

    /**
     * Locate on the whole page, then read each region again from a crop blown
     * up several times.
     *
     * The idea being tested is that ML Kit's trouble is glyph size rather than
     * the lettering itself: finding roughly where text is needs far less
     * resolution than reading it.
     */
    private suspend fun twoPass(
        recognizer: MlKitTextRecognizer,
        grouper: TextRegionGrouper,
        bitmap: Bitmap,
    ): List<String> {
        val located = grouper.group(recognizer.recognize(bitmap))

        return located.map { region ->
            val crop = bitmap.cropWithMargin(region.bounds) ?: return@map region.text
            val magnified = crop.scaledToWidth(crop.width * MAGNIFY)
            val lines = recognizer.recognize(magnified)

            magnified.recycle()
            if (crop !== bitmap) crop.recycle()

            // Grouped again so the columns inside the crop are ordered by the
            // same rules; falling back when the closer look finds nothing.
            grouper.group(lines).joinToString("") { it.text }.ifBlank { region.text }
        }
    }

    private fun Bitmap.scaledToWidth(width: Int): Bitmap {
        if (width == this.width) return this
        val height = (height.toLong() * width / this.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, width, height, true)
    }

    /** Margin included: a tight crop can clip the strokes at the edges. */
    private fun Bitmap.cropWithMargin(bounds: TextBounds): Bitmap? {
        val margin = maxOf(bounds.width, bounds.height) / 10
        val left = (bounds.left - margin).coerceIn(0, width)
        val top = (bounds.top - margin).coerceIn(0, height)
        val right = (bounds.right + margin).coerceIn(0, width)
        val bottom = (bounds.bottom + margin).coerceIn(0, height)
        if (right - left <= 0 || bottom - top <= 0) return null
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    /** Greyscale with the contrast pushed, aimed at screentone pages. */
    private fun Bitmap.highContrast(): Bitmap {
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val saturation = ColorMatrix().apply { setSaturation(0f) }
        val stretch = ColorMatrix(
            floatArrayOf(
                CONTRAST, 0f, 0f, 0f, CONTRAST_SHIFT,
                0f, CONTRAST, 0f, 0f, CONTRAST_SHIFT,
                0f, 0f, CONTRAST, 0f, CONTRAST_SHIFT,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        saturation.postConcat(stretch)

        Canvas(output).drawBitmap(
            this,
            0f,
            0f,
            Paint().apply { colorFilter = ColorMatrixColorFilter(saturation) },
        )
        return output
    }

    private companion object {
        const val MAGNIFY = 3
        const val CONTRAST = 1.8f
        const val CONTRAST_SHIFT = -100f
    }
}
