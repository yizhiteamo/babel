package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextRegionGrouper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Asks how good the result would be **if bubbles were detected perfectly**,
 * before anything is built to detect them.
 *
 * Three of the remaining defects are one question wearing three hats: a bubble
 * split into two half-translations, sound effects and cover lettering getting
 * opaque boxes, and a box crossing a balloon's outline. All three are "where is
 * the bubble". Detecting that properly means a trained model, which means
 * weights, a licence and a runtime — worth knowing the payoff before spending
 * any of it.
 *
 * Two attempts to answer the question from local pixels have already failed:
 * flooding leaked through balloon tails, and sampling the gap between columns
 * could not tell open sky from a bubble's inside. Both sounded right and
 * measured worse. So this measures the ceiling first.
 *
 * ## How the oracle works
 *
 * Bubbles are given as rectangles in fractions of the page, and a line belongs
 * to whichever one contains its centre. Lines inside none are dropped, which is
 * what a perfect filter would do to sound effects and cover lettering.
 *
 * Deciding it from the transcriptions instead was tried twice and cannot work.
 * A strict text match threw out columns that OCR had misread, charging OCR's
 * errors to grouping and producing a "ceiling" *below* the current result. A
 * looser one then put `先生?` from one balloon into another, because that text
 * appears in both and **no amount of text comparison can separate two bubbles
 * that say the same thing**. Position separates them immediately.
 *
 * The rectangles are read off measured line coordinates rather than drawn by
 * eye, so they carry the bubbles' real extents.
 *
 * What this **cannot** show is box shape — the oracle says which lines belong
 * together, not where the balloon's outline runs. So the figure it produces is
 * the ceiling for grouping and filtering only, and the real ceiling is higher.
 */
@RunWith(AndroidJUnit4::class)
class BubbleOracleExperimentTest {

    @Test
    fun measureCeiling() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.getExternalFilesDir(null), "comic-sample")

        val pages = BubbleScoring.groundTruth.keys
            .map { File(dir, it) }
            .filter { it.exists() }

        if (pages.isEmpty()) {
            println("ORACLE skipped: no material with ground truth in ${dir.absolutePath}")
            return@runBlocking
        }

        val recognizer = MlKitTextRecognizer(BabelLogger.NoOp)
        val grouper = TextRegionGrouper()

        for (page in pages) {
            val expected = BubbleScoring.groundTruth.getValue(page.name)
            val original = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            // As the app sees it: a page fitted to a portrait screen.
            val displayed = Bitmap.createScaledBitmap(
                original,
                SCREEN_WIDTH,
                (original.height.toLong() * SCREEN_WIDTH / original.width).toInt(),
                true,
            )

            val lines = recognizer.recognize(displayed)

            val actual = grouper.group(lines).map { it.text }
            val oracle = groupByGroundTruth(lines, page.name, displayed.width, displayed.height)
            val dropped = lines.size - oracle.sumOf { it.second }

            println(
                "ORACLE ${page.name} today=${BubbleScoring.percent(BubbleScoring.pageScore(expected, actual))}" +
                    " ceiling=${BubbleScoring.percent(BubbleScoring.pageScore(expected, oracle.map { it.first }))}" +
                    " regions=${actual.size}->${oracle.size}" +
                    " linesDroppedAsNonBubble=$dropped/${lines.size}",
            )
            oracle.forEach { (text, _) ->
                val best = expected.maxByOrNull { BubbleScoring.similarity(text, it) }
                println(
                    "ORACLE     ${BubbleScoring.percent(BubbleScoring.similarity(text, best.orEmpty()))}" +
                        " got=\"$text\"",
                )
            }

            displayed.recycle()
            original.recycle()
        }
    }

    /**
     * Perfect grouping: every line placed in the bubble it came from, in reading
     * order, and anything belonging to no bubble discarded.
     *
     * @return each bubble's joined text with the number of lines that formed it.
     */
    private fun groupByGroundTruth(
        lines: List<RecognizedLine>,
        page: String,
        width: Int,
        height: Int,
    ): List<Pair<String, Int>> = lines
        .groupBy { line ->
            BUBBLES.getValue(page).firstOrNull { it.contains(line.bounds, width, height) }
        }
        .filterKeys { it != null }
        .map { (_, group) ->
            // Right to left, then down — the reading order the grouper applies
            // to vertical Japanese, restated here because the oracle is not
            // going through the grouper.
            val ordered = group
                .sortedWith(compareByDescending<RecognizedLine> { it.bounds.right }.thenBy { it.bounds.top })
                .joinToString("") { it.text }
            ordered to group.size
        }


    /** A bubble as a fraction of the page, so it survives any scaling. */
    private data class Bubble(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
    ) {
        fun contains(bounds: com.babel.core.model.TextBounds, width: Int, height: Int): Boolean {
            val x = (bounds.left + bounds.right) / 2.0 / width
            val y = (bounds.top + bounds.bottom) / 2.0 / height
            return x in left..right && y in top..bottom
        }
    }

    private companion object {
        const val SCREEN_WIDTH = 1080

        /**
         * Where the balloons are, taken from measured line positions on each
         * page with a little margin. Sound effects and the lettering on the
         * book cover are deliberately outside every rectangle: a perfect
         * detector would not offer them for translation either.
         */
        val BUBBLES = mapOf(
            "jap-mag-01.jpg" to listOf(
                Bubble(0.79, 0.02, 0.97, 0.25),
                Bubble(0.11, 0.02, 0.18, 0.14),
                Bubble(0.01, 0.16, 0.18, 0.35),
                Bubble(0.78, 0.47, 0.92, 0.72),
                Bubble(0.00, 0.52, 0.18, 0.74),
            ),
            "jap-mag-04.jpg" to listOf(
                Bubble(0.88, 0.00, 1.00, 0.19),
                Bubble(0.42, 0.03, 0.51, 0.15),
                Bubble(0.81, 0.33, 0.94, 0.57),
                Bubble(0.55, 0.47, 0.71, 0.72),
                Bubble(0.39, 0.33, 0.45, 0.49),
                Bubble(0.06, 0.54, 0.17, 0.71),
                Bubble(0.90, 0.76, 0.99, 0.91),
                Bubble(0.55, 0.73, 0.61, 0.92),
            ),
        )
        /** Below this many characters a line is judged almost exactly. */
        const val SHORT_LINE = 3
        const val LONG_FLOOR = 0.5
        const val SHORT_FLOOR = 0.9
    }
}
