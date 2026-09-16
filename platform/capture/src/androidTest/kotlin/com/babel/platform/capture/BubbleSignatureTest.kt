package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import java.io.File
import kotlin.test.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Why there is no recognition cache.** This is the experiment that ruled one
 * out; the numbers it produces are recorded in `docs/milestones/v2.md`.
 *
 * A cache keyed on what a balloon looks like has two failure modes, and they
 * pull in opposite directions:
 *
 * - **Collision** — two different balloons share a signature, so one balloon's
 *   translation is drawn over another. Silent, and worse than any slowness.
 * - **Miss** — the same balloon cropped a pixel differently fails to match, so
 *   nothing is cached and the page costs what it already costs.
 *
 * A miss is free. A collision is a bug. So this asserts on collisions and only
 * *reports* the hit rate, because the balance between the two is a judgement
 * about real pages rather than a threshold worth failing a build over.
 *
 * ```
 * ADB=<path-to-adb> bash docs/testing/push-comic-sample.sh
 * ./gradlew :platform:capture:connectedDebugAndroidTest --tests "*BubbleSignatureTest"
 * adb logcat -d | grep SIGNATURE
 * ```
 */
@RunWith(AndroidJUnit4::class)
class BubbleSignatureTest {

    private val dispatchers = object : DispatcherProvider {
        override val main = Dispatchers.Main
        override val default = Dispatchers.Default
        override val io = Dispatchers.IO
    }

    @Test
    fun differentBalloonsNeverShareASignature() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            .orEmpty()

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        if (pages.isEmpty() || !detector.isAvailable) {
            println("SIGNATURE skipped: needs comic-sample and detector.onnx pushed")
            return@runBlocking
        }

        // Signature -> the crops that produced it, described well enough to tell
        // whether a shared signature is the same balloon or two different ones.
        val seen = mutableMapOf<BubbleSignature, MutableList<String>>()
        var balloons = 0

        for (page in pages) {
            val bitmap = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue

            for (bubble in detector.detect(bitmap)) {
                val crop = bitmap.cropTo(bubble.text) ?: continue
                val signature = BubbleSignature.of(crop)
                crop.recycle()
                if (signature == null) continue

                balloons++
                val where = "${page.name}@${bubble.text.left},${bubble.text.top}" +
                    " ${bubble.text.width}x${bubble.text.height}"
                seen.getOrPut(signature) { mutableListOf() } += where
            }

            bitmap.recycle()
        }

        val collisions = seen.filterValues { it.size > 1 }
        println("SIGNATURE $balloons balloons over ${pages.size} pages, " +
            "${seen.size} distinct signatures, ${collisions.size} shared")
        collisions.forEach { (_, where) -> println("SIGNATURE   shared by: $where") }

        // Distinctness was never the problem — this passes. The problem is the
        // other side, measured by the two tests below: the same balloon does not
        // reproduce its signature once the page has moved.
        assertEquals(emptyMap(), collisions, "two balloons share a signature")
    }

    /**
     * The other half: a balloon found again after the page moved must still
     * match, or the cache never hits and the whole thing is dead weight.
     *
     * Simulated by shifting the crop by a pixel, which is what a detector box
     * does between scans of a scrolled page.
     */
    /**
     * Sweeps the balance, because the two requirements pull against each other
     * and neither can be reasoned about from first principles.
     *
     * Finer grids and more levels separate balloons better and tolerate a moved
     * crop worse. What matters is whether a setting exists that does both on
     * real pages — and if none does, that is worth finding out before shipping a
     * cache that never hits.
     *
     * Reports rather than asserts: this chooses the constants, it does not guard
     * them. `differentBalloonsNeverShareASignature` guards them.
     */
    @Test
    fun sweepTheBalanceBetweenCollidingAndMissing() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            .orEmpty()

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        if (pages.isEmpty() || !detector.isAvailable) {
            println("SIGNATURE skipped: needs comic-sample and detector.onnx pushed")
            return@runBlocking
        }

        // Each balloon cropped exactly, and again shifted — which is what a
        // detector box does to the same balloon after the page has scrolled.
        val exact = mutableListOf<Bitmap>()
        val shifted = mutableListOf<Bitmap>()

        for (page in pages) {
            val bitmap = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            for (bubble in detector.detect(bitmap)) {
                val here = bitmap.cropTo(bubble.text) ?: continue
                val moved = bitmap.cropTo(bubble.text.movedBy(1, 1))
                if (moved == null) {
                    here.recycle()
                    continue
                }
                exact += here
                shifted += moved
            }
            bitmap.recycle()
        }

        // Exact equality first, which is what a hash map would need.
        println("SIGNATURE sweep over ${exact.size} balloons")
        println("SIGNATURE grid levels  distinct  shared  survives-1px")
        for (grid in listOf(8, 12, 16, 24)) {
            for (levels in listOf(2, 4, 8, 16)) {
                val signatures = exact.mapNotNull { BubbleSignature.of(it, grid, levels) }
                val distinct = signatures.toSet().size
                val survived = exact.indices.count { index ->
                    val before = BubbleSignature.of(exact[index], grid, levels)
                    val after = BubbleSignature.of(shifted[index], grid, levels)
                    before != null && before == after
                }
                println(
                    "SIGNATURE %4d %6d  %8d  %6d  %5d/%d".format(
                        grid, levels, distinct, exact.size - distinct, survived, exact.size,
                    ),
                )
            }
        }

        // Then with a tolerance, which is what a short scanned list could use.
        // The question is whether a gap exists: is a balloon always closer to
        // its own shifted self than to any other balloon? If it is, a threshold
        // fits in the gap. If not, no amount of tuning saves this.
        println("SIGNATURE --- mean absolute difference per cell ---")
        println("SIGNATURE grid  self-shift(max)  other(min)  gap")
        for (grid in listOf(8, 12, 16, 24)) {
            val grids = exact.mapNotNull { BubbleSignature.gridOf(it, grid) }
            val moved = shifted.mapNotNull { BubbleSignature.gridOf(it, grid) }
            if (grids.size != exact.size || moved.size != exact.size) continue

            val selfWorst = grids.indices.maxOf { distance(grids[it], moved[it]) }
            val otherBest = grids.indices.minOf { a ->
                grids.indices.filter { it != a }.minOf { b -> distance(grids[a], grids[b]) }
            }
            println(
                "SIGNATURE %4d %15.1f %11.1f %5.1f".format(
                    grid, selfWorst, otherBest, otherBest - selfWorst,
                ),
            )
        }

        exact.forEach(Bitmap::recycle)
        shifted.forEach(Bitmap::recycle)
    }

    /**
     * What actually happens on a scroll, rather than the worst case.
     *
     * The sweep above shifts a crop relative to its balloon and finds that
     * nothing survives it. But a browser scrolls by whole pixels: the balloon is
     * rendered identically at a new screen offset, so the pixels are the same
     * ones. The only question is whether the **detector** puts its box at the
     * same place relative to the balloon on a frame that has moved — and it runs
     * on a 640x640 resize of the whole screen, so it might not.
     *
     * Simulated by detecting on two viewports of the same page, one scrolled
     * past the other, and comparing the crops of balloons that appear in both.
     */
    @Test
    fun theSameBalloonSeenTwiceAfterAScroll() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pages = File(context.getExternalFilesDir(null), "comic-sample")
            .listFiles { file -> file.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            .orEmpty()

        val detector = OnnxBubbleDetector(context, dispatchers, BabelLogger.NoOp)
        if (pages.isEmpty() || !detector.isAvailable) {
            println("SIGNATURE skipped: needs comic-sample and detector.onnx pushed")
            return@runBlocking
        }

        val scroll = 120
        var carried = 0
        var identical = 0
        var matched = 0

        for (page in pages) {
            val full = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue
            val viewportHeight = minOf(full.height - scroll, full.width * 9 / 16)
            if (viewportHeight < 200) {
                full.recycle()
                continue
            }

            // Two "screens" of the same page, one scrolled past the other.
            val before = Bitmap.createBitmap(full, 0, 0, full.width, viewportHeight)
            val after = Bitmap.createBitmap(full, 0, scroll, full.width, viewportHeight)

            val firstPass = detector.detect(before)
            val secondPass = detector.detect(after)

            for (bubble in firstPass) {
                // Where this balloon sits in the scrolled viewport, if at all.
                val expectedTop = bubble.text.top - scroll
                if (expectedTop < 0) continue
                val again = secondPass.minByOrNull {
                    kotlin.math.abs(it.text.left - bubble.text.left) +
                        kotlin.math.abs(it.text.top - expectedTop)
                } ?: continue
                // Only count it as the same balloon if the boxes really coincide.
                if (kotlin.math.abs(again.text.left - bubble.text.left) > 24) continue
                if (kotlin.math.abs(again.text.top - expectedTop) > 24) continue

                val cropBefore = before.cropTo(bubble.text) ?: continue
                val cropAfter = after.cropTo(again.text)
                if (cropAfter == null) {
                    cropBefore.recycle()
                    continue
                }

                carried++
                val sameBox = bubble.text.width == again.text.width &&
                    bubble.text.height == again.text.height &&
                    bubble.text.left == again.text.left &&
                    bubble.text.top == expectedTop
                if (sameBox) identical++
                if (BubbleSignature.of(cropBefore) == BubbleSignature.of(cropAfter)) matched++

                cropBefore.recycle()
                cropAfter.recycle()
            }

            before.recycle()
            after.recycle()
            full.recycle()
        }

        println(
            "SIGNATURE scrolled by ${scroll}px: $carried balloons seen twice, " +
                "$identical with an identical box, $matched matching signatures",
        )
    }

    /** Mean absolute luminance difference per cell, 0..255. */
    private fun distance(a: IntArray, b: IntArray): Double {
        var total = 0L
        for (index in a.indices) total += kotlin.math.abs(a[index] - b[index])
        return total.toDouble() / a.size
    }

    private fun com.babel.core.model.TextBounds.movedBy(dx: Int, dy: Int) =
        copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)

    private fun Bitmap.cropTo(bounds: com.babel.core.model.TextBounds): Bitmap? {
        val left = bounds.left.coerceIn(0, width)
        val top = bounds.top.coerceIn(0, height)
        val right = bounds.right.coerceIn(left, width)
        val bottom = bounds.bottom.coerceIn(top, height)
        if (right - left < 8 || bottom - top < 8) return null
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }
}
