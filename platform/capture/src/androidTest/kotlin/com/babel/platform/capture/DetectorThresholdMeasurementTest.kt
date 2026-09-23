package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
 * What the detector's two open questions actually cost, in numbers and pictures.
 *
 * Eight pages were run end to end on a device and seven were good. The eighth,
 * `jap-mag-07`, is a dark rain scene, and it was written down as a detector
 * fault: the model was supposed to be boxing bright streaks — rain, highlights,
 * panel edges — as balloons. Two levers were proposed, raising `SCORE_FLOOR`
 * and demanding an enclosing balloon, and both could do real harm, so both were
 * left alone until they could be measured.
 *
 * **This measured them, and the diagnosis was wrong.** Page 07's five boxes
 * score 0.89 to 0.91 and every one is paired with a balloon; across all eight
 * pages not a single class-1 box is unenclosed. No floor removes them without
 * removing nearly every balloon in the sample, and the enclosure rule removes
 * nothing anywhere. They are five real balloons holding one sentence split five
 * ways, which is the unit-of-translation problem wearing a dark page.
 *
 * So it stays an instrument rather than becoming a fix. Run it again before
 * touching either constant.
 *
 * Separately, `LABEL_TEXT_FREE` used to be read out of the model and dropped,
 * so that sound effects would not be translated under an opaque box. The cost
 * was visible on two pages: page 05's hand-lettered dialogue and the whole of
 * page 06, a character sheet with no balloons at all. This is what counted it —
 * seven of nine boxes were real text — and the class is now kept, with the
 * sound effects separated after reading by `SoundEffect`.
 *
 * Both questions need the same thing — **per-box human judgement on all eight
 * pages** — so this produces it once. It prints tables and writes an annotated
 * copy of each page; it asserts almost nothing, like
 * `FrameSignatureMeasurementTest` and `BubbleSignatureTest`. An instrument, not
 * a gate.
 *
 * One inference per page: the sweep filters what came back rather than running
 * the model again, so five floors cost what one does.
 *
 * ```
 * bash docs/testing/push-comic-sample.sh
 * ./gradlew :platform:capture:connectedDebugAndroidTest
 *   -Pandroid.testInstrumentationRunnerArguments.class=
 *     com.babel.platform.capture.DetectorThresholdMeasurementTest
 * adb logcat -d | grep SWEEP
 * adb pull /sdcard/Android/data/com.babel.platform.capture.test/files/detector-sweep
 * ```
 *
 * The pulled images are derived from copyrighted material. They stay out of the
 * repository exactly as the pages do.
 */
@RunWith(AndroidJUnit4::class)
class DetectorThresholdMeasurementTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    /** Low enough to catch everything either lever would ever be set to. */
    private val collectionFloor = 0.05f

    private val floors = listOf(0.30f, 0.40f, 0.50f, 0.60f, 0.70f)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * One instance, used for both inference and pairing.
     *
     * Pairing goes through the production function rather than a restatement of
     * its geometry: containment-not-IoU is a decision this is measuring, and a
     * second copy of it would measure the copy.
     */
    private val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)

    @Test
    fun measureWhatEachLeverWouldDrop() = runBlocking {
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() in IMAGE_TYPES }
            ?.sortedBy { it.name }
            .orEmpty()

        if (pages.isEmpty()) {
            println("SWEEP skipped: needs comic-sample pushed")
            return@runBlocking
        }

        val output = File(context.getExternalFilesDir(null), "detector-sweep")
        output.mkdirs()
        output.listFiles()?.forEach { it.delete() }

        println(
            "SWEEP production today: SCORE_FLOOR=${OnnxBubbleDetector.SCORE_FLOOR}, " +
                "open regions allowed, class ${OnnxBubbleDetector.LABEL_TEXT_FREE} kept " +
                "and filtered after reading",
        )
        println("SWEEP collected at floor $collectionFloor and filtered afterwards")

        val perPage = mutableListOf<Pair<String, List<RawDetection>>>()

        for (file in pages) {
            val page = BitmapFactory.decodeFile(file.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            val raw = detector.detectRaw(page, collectionFloor)
            perPage += file.name to raw
            annotate(page, raw, File(output, "${file.nameWithoutExtension}-boxes.png"))
            page.recycle()
        }

        detector.release()

        reportSweep(perPage)
        reportFreeText(perPage)
        reportScores(perPage)

        println("SWEEP")
        println("SWEEP annotated pages written to ${output.absolutePath}")
    }

    /**
     * The table the page-07 decision comes from.
     *
     * Two blocks because the levers interact: requiring an enclosing balloon may
     * already remove what a higher floor would, in which case only one of them
     * is needed.
     */
    private fun reportSweep(perPage: List<Pair<String, List<RawDetection>>>) {
        println("SWEEP")
        println("SWEEP === bubbles that would be read (class 1 only, as today) ===")
        println(
            "SWEEP   page         " + floors.joinToString(" ") { " %.2f".format(it) } +
                "  |" + floors.joinToString(" ") { " %.2f".format(it) } + "  (enclosed only)",
        )

        for ((name, raw) in perPage) {
            val open = floors.map { floor -> inBalloons(raw, floor).size }
            val enclosed = floors.map { floor ->
                inBalloons(raw, floor).count { it.balloon != null }
            }
            println(
                "SWEEP   %-12s %s  |%s".format(
                    name.removeSuffix(".jpg"),
                    open.joinToString("") { "%6d".format(it) },
                    enclosed.joinToString("") { "%6d".format(it) },
                ),
            )
        }
        println("SWEEP")
        println("SWEEP   an unenclosed region is legitimate unboxed dialogue as often as it is")
        println("SWEEP   a false positive — the annotated pages are what separates them")
    }

    /** The table the free-text decision comes from. */
    private fun reportFreeText(perPage: List<Pair<String, List<RawDetection>>>) {
        println("SWEEP")
        println("SWEEP === class ${OnnxBubbleDetector.LABEL_TEXT_FREE} (free text) ===")
        println("SWEEP   page         raw  kept  scores / sizes")
        for ((name, raw) in perPage) {
            val free = raw.filter {
                it.label == OnnxBubbleDetector.LABEL_TEXT_FREE &&
                    it.score >= OnnxBubbleDetector.SCORE_FLOOR
            }
            // Raw and kept differ where the model labelled one region twice.
            val kept = detector.pair(above(raw, OnnxBubbleDetector.SCORE_FLOOR)).count { it.onArt }
            val detail = free.joinToString("  ") {
                "%.2f@%dx%d".format(it.score, it.box.width, it.box.height)
            }
            println(
                "SWEEP   %-12s %3d %5d  %s".format(
                    name.removeSuffix(".jpg"),
                    free.size,
                    kept,
                    detail,
                ),
            )
        }
        println("SWEEP")
        println("SWEEP   size does not separate noise from speech — the script does, after")
        println("SWEEP   reading. raw minus kept is the model labelling one region twice.")
    }

    /**
     * Every class-1 box with its score, so the ones page 07 gains can be found
     * in the list rather than inferred from a count.
     */
    private fun reportScores(perPage: List<Pair<String, List<RawDetection>>>) {
        println("SWEEP")
        println("SWEEP === class 1 boxes above %.2f, with scores ===".format(collectionFloor))
        for ((name, raw) in perPage) {
            val enclosed = inBalloons(raw, collectionFloor)
                .filter { it.balloon != null }
                .map { it.text }
                .toSet()
            val detail = raw.filter { it.label == OnnxBubbleDetector.LABEL_TEXT_IN_BUBBLE }
                .sortedByDescending { it.score }
                .joinToString("  ") { detection ->
                    "%.2f%s".format(detection.score, if (detection.box in enclosed) "" else "*")
                }
            println("SWEEP   %-12s %s".format(name.removeSuffix(".jpg"), detail))
        }
        println("SWEEP   * = no enclosing balloon")
    }

    private fun above(raw: List<RawDetection>, floor: Float) = raw.filter { it.score >= floor }

    /**
     * The class-1 half of the pairing.
     *
     * `pair` returns free text as well now, which is the change this test
     * measured the case for. The balloon table stays about balloons.
     */
    private fun inBalloons(raw: List<RawDetection>, floor: Float) =
        detector.pair(above(raw, floor)).filterNot { it.onArt }

    /**
     * The page with every box drawn on it, labelled with class and score.
     *
     * Counts cannot answer "is that a balloon or is it rain"; a picture can, and
     * eight pages of them is the ground truth this decision has been missing.
     */
    private fun annotate(page: Bitmap, raw: List<RawDetection>, target: File) {
        val annotated = page.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val stroke = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(2f, annotated.width / 400f)
            isAntiAlias = true
        }
        val label = Paint().apply {
            isAntiAlias = true
            textSize = maxOf(18f, annotated.width / 55f)
        }
        val backing = Paint().apply { color = Color.BLACK }

        // Balloons first, so lettering draws over them rather than under.
        val ordered = raw.sortedBy { if (it.label == OnnxBubbleDetector.LABEL_BALLOON) 0 else 1 }
        for (detection in ordered) {
            val colour = when (detection.label) {
                OnnxBubbleDetector.LABEL_BALLOON -> Color.rgb(0, 160, 255)
                OnnxBubbleDetector.LABEL_TEXT_IN_BUBBLE -> Color.rgb(0, 200, 60)
                else -> Color.rgb(255, 120, 0)
            }
            stroke.color = colour
            val box = detection.box
            canvas.drawRect(
                box.left.toFloat(),
                box.top.toFloat(),
                box.right.toFloat(),
                box.bottom.toFloat(),
                stroke,
            )

            val text = "%d %.2f".format(detection.label, detection.score)
            val baseline = box.top + label.textSize
            canvas.drawRect(
                box.left.toFloat(),
                baseline - label.textSize,
                box.left + label.measureText(text) + 6f,
                baseline + 4f,
                backing,
            )
            label.color = colour
            canvas.drawText(text, box.left + 3f, baseline, label)
        }

        target.outputStream().use { annotated.compress(Bitmap.CompressFormat.PNG, 100, it) }
        annotated.recycle()
    }

    private companion object {
        val IMAGE_TYPES = setOf("jpg", "jpeg", "png", "webp")
    }
}
