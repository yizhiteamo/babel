package com.babel.platform.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import java.io.File
import kotlin.math.abs
import kotlin.math.max
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
 * # jap-mag-14/15/16 (or the pages named) into the test package's files/comic-sample, models as for PageTextDumpTest
 * adb shell am instrument -w -e class
 *   com.babel.platform.capture.ReadingVarianceMeasurementTest
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep -E "VARIANCE|CROPS"
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
        val column = column(context) ?: return@runBlocking
        val engines = Engines(context)

        var offset = FIRST
        while (offset <= LAST) {
            val window = Bitmap.createBitmap(column, 0, offset, WIDTH, HEIGHT)
            // A fresh reader each time: no memory, so every balloon is read anew.
            val reader = DetectingPageReader(
                engines.detector, engines.recognizer, GroupingPageReader(engines.general), BabelLogger.NoOp,
            )
            reader.read(window) { region ->
                val b = region.bounds
                println("VARIANCE $offset ${b.left},${b.top + offset},${b.width},${b.height}\t${region.text}")
                true
            }
            window.recycle()
            offset += STEP
        }
        println("VARIANCE done")
        engines.release()
    }

    /**
     * The same detections read through differently made crops, to see which
     * one the box's wandering stops mattering to.
     *
     * - `box`: the detector's text box, as the pipeline crops it now
     * - `trim`: the box cut back to where its ink is, padded by [PAD]
     * - `reach`: the same, but looking [REACH] beyond the box first, so a box
     *   that cut the lettering short gets it back
     * - `square`: the box, centred on a square of its own background — manga-ocr
     *   stretches every crop to 224x224, so a box 126 or 209px tall gives the
     *   same title two different shapes
     *
     * None of them won (`docs/milestones/v2.md`, *同一气泡每次读得不一样*):
     * `trim` steadied the stylised titles of pages 14–16 and unsettled the
     * clean dialogue of pages 10–12; `reach` pulled in neighbouring balloons;
     * `square` made manga-ocr invent sentences.
     *
     * Balloons the viewport cuts are skipped, as the pipeline skips them.
     * Pages: `-e pages jap-mag-10.jpg,jap-mag-11.jpg,jap-mag-12.jpg`.
     */
    @Test
    fun compareCropsAcrossOffsets() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val column = column(context) ?: return@runBlocking
        val engines = Engines(context)
        val spent = LongArray(VARIANTS.size)

        var offset = FIRST
        while (offset <= LAST) {
            val window = Bitmap.createBitmap(column, 0, offset, WIDTH, HEIGHT)
            engines.recognizer.startPage(window)
            for (bubble in engines.detector.detect(window)) {
                val box = bubble.text
                if (box.top <= 0 || box.bottom >= HEIGHT) continue
                val ink = inkBox(window, box, 0f)
                for ((index, variant) in VARIANTS.withIndex()) {
                    val started = System.currentTimeMillis()
                    val crop = when (variant) {
                        "box" -> window.cut(box)
                        "trim" -> window.cut(ink)
                        "reach" -> window.cut(inkBox(window, box, REACH))
                        else -> window.cut(box)?.squared()
                    } ?: continue
                    val text = engines.recognizer.recognize(crop).joinToString("") { it.text }
                    spent[index] += System.currentTimeMillis() - started
                    crop.recycle()
                    println(
                        "CROPS $variant $offset ${box.left},${box.top + offset},${box.width},${box.height} " +
                            "ink=${ink.width}x${ink.height}\t$text",
                    )
                }
            }
            window.recycle()
            offset += WIDE_STEP
        }
        VARIANTS.forEachIndexed { index, variant -> println("CROPS time $variant ${spent[index]}ms") }
        println("CROPS done")
        engines.release()
    }

    /**
     * Where the ink is, near [box]: the box grown by [reach] on every side,
     * then cut back to the runs of inked rows and columns that touch the box
     * itself, then padded by [PAD]. A run is broken only by a gap of blank
     * rows, so a balloon's outline beyond the margin stays out.
     */
    private fun inkBox(frame: Bitmap, box: TextBounds, reach: Float): TextBounds {
        val growX = (box.width * reach).toInt()
        val growY = (box.height * reach).toInt()
        val left = (box.left - growX).coerceAtLeast(0)
        val top = (box.top - growY).coerceAtLeast(0)
        val right = (box.right + growX).coerceAtMost(frame.width)
        val bottom = (box.bottom + growY).coerceAtMost(frame.height)
        val w = right - left
        val h = bottom - top
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, left, top, w, h)
        val grey = IntArray(pixels.size) { grey(pixels[it]) }

        val border = ArrayList<Int>(2 * (w + h))
        for (x in 0 until w) { border += grey[x]; border += grey[(h - 1) * w + x] }
        for (y in 0 until h) { border += grey[y * w]; border += grey[y * w + w - 1] }
        border.sort()
        val background = border[border.size / 2]
        val inked = BooleanArray(grey.size) { abs(grey[it] - background) >= INK_CONTRAST }

        val rows = IntArray(h) { y -> (0 until w).count { inked[y * w + it] } }
        val (y0, y1) = runsTouching(rows, box.top - top, box.bottom - top, max(4, h / 50))
            ?: return box
        val cols = IntArray(w) { x -> (y0..y1).count { inked[it * w + x] } }
        val (x0, x1) = runsTouching(cols, box.left - left, box.right - left, max(4, w / 50))
            ?: return box

        val pad = (max(x1 - x0, y1 - y0) * PAD).toInt()
        return TextBounds(
            (left + x0 - pad).coerceAtLeast(0),
            (top + y0 - pad).coerceAtLeast(0),
            (left + x1 + 1 + pad).coerceAtMost(frame.width),
            (top + y1 + 1 + pad).coerceAtMost(frame.height),
            CoordinateSpace.SCREEN,
        )
    }

    /** The extent of the inked runs that overlap [from, to), joined across gaps under [gap]. */
    private fun runsTouching(counts: IntArray, from: Int, to: Int, gap: Int): Pair<Int, Int>? {
        val runs = mutableListOf<IntArray>()
        var start = -1
        var lastInk = -1
        for (i in counts.indices) {
            if (counts[i] < MIN_INK) continue
            if (start < 0) start = i
            else if (i - lastInk > gap) { runs += intArrayOf(start, lastInk); start = i }
            lastInk = i
        }
        if (start >= 0) runs += intArrayOf(start, lastInk)
        val touching = runs.filter { it[1] >= from && it[0] < to }
        if (touching.isEmpty()) return null
        return touching.minOf { it[0] } to touching.maxOf { it[1] }
    }

    private fun grey(pixel: Int) =
        (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000

    private fun Bitmap.cut(bounds: TextBounds): Bitmap? {
        val l = bounds.left.coerceIn(0, width)
        val t = bounds.top.coerceIn(0, height)
        val r = bounds.right.coerceIn(l, width)
        val b = bounds.bottom.coerceIn(t, height)
        if (r - l < 8 || b - t < 8) return null
        return Bitmap.createBitmap(this, l, t, r - l, b - t)
    }

    /** Centred on a square filled with its own edge colour. */
    private fun Bitmap.squared(): Bitmap {
        val side = max(width, height)
        val edge = IntArray(2 * (width + height))
        var n = 0
        for (x in 0 until width) { edge[n++] = getPixel(x, 0); edge[n++] = getPixel(x, height - 1) }
        for (y in 0 until height) { edge[n++] = getPixel(0, y); edge[n++] = getPixel(width - 1, y) }
        val background = edge.sortedBy { grey(it) }[edge.size / 2]
        val square = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(square).apply {
            drawColor(background)
            drawBitmap(this@squared, ((side - width) / 2).toFloat(), ((side - height) / 2).toFloat(), null)
        }
        recycle()
        return square
    }

    private fun column(context: Context): Bitmap? {
        val dir = File(context.getExternalFilesDir(null), "comic-sample")
        val names = InstrumentationRegistry.getArguments().getString("pages")?.split(",")
            ?: listOf("jap-mag-14.jpg", "jap-mag-15.jpg", "jap-mag-16.jpg")
        if (names.any { !File(dir, it).exists() }) {
            println("VARIANCE skipped")
            return null
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
        return column
    }

    private inner class Engines(context: Context) {
        val manga = MangaOcrRecognizer(context, dispatchers, BabelLogger.NoOp)
        val general = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.JAPANESE)
        val korean = MlKitTextRecognizer(BabelLogger.NoOp, MlKitTextRecognizer.Script.KOREAN)
        val recognizer = BubbleRecognizer(manga, general, korean)
        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)

        suspend fun release() {
            recognizer.release()
            detector.release()
        }
    }

    private companion object {
        /** Phone width, and Edge's content height on that phone. */
        const val WIDTH = 1440
        const val HEIGHT = 3062

        /** From the first page's balloons to the end of the column. */
        const val FIRST = 700
        const val LAST = 3847
        const val STEP = 37

        /** Four reads a balloon, so half as many windows. */
        const val WIDE_STEP = 74

        val VARIANTS = listOf("box", "trim", "reach", "square")

        /** 0.08 and 0.25 were tried; both pulled in the next balloon. */
        const val REACH = 0.08f

        /** 0 and 0.12 were tried; 0.06 read best on pages 14–16. */
        const val PAD = 0.06f
        const val INK_CONTRAST = 80
        const val MIN_INK = 2
    }
}
