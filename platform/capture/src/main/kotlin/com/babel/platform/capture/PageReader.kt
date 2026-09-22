package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.common.BabelLogger
import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextRegion
import com.babel.domain.vision.TextRegionGrouper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a captured page into the regions that get translated.
 *
 * Two ways of doing it, because there are two answers to "where is a bubble"
 * and only one of them needs a model on the device.
 */
internal interface PageReader {

    /**
     * Hands each region over as it is read, rather than the page at the end.
     *
     * Reading a page costs seconds — measured across eight real pages, 1.1s to
     * 4.9s, and the slowest had only three balloons because the decoder has no
     * key/value cache and its cost grows with the square of the text. Returning
     * a list made every translation wait for the worst balloon on the page. A
     * caller that publishes as it goes can put the first one on screen in about
     * a second and a half instead.
     *
     * [onRegion] is called on the reading coroutine, in reading order.
     */
    suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Unit)

    /** Lets go of loaded models. Reading again afterwards reloads them. */
    suspend fun release() = Unit
}

/**
 * Reads the whole page, then groups the lines by where they sit.
 *
 * What V2 shipped with, and what runs when no detector model is present. Its
 * limits are measured and recorded: a bubble split across two regions, sound
 * effects offered for translation, and a box crossing a balloon's outline
 * (`docs/milestones/v2.md`).
 */
@Singleton
internal class GroupingPageReader @Inject constructor(
    private val recognizer: TextRecognizer,
) : PageReader {

    private val grouper = TextRegionGrouper()

    /**
     * Emits at the end regardless: this path recognises the whole page in one
     * call and only then knows where anything is, so there is nothing to hand
     * over early. The streaming shape belongs to the caller, not to every
     * reader.
     */
    override suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Unit) {
        val lines = recognizer.recognize(frame)
        if (lines.isEmpty()) return
        grouper.group(lines).forEach { onRegion(it) }
    }
}

/**
 * Finds the balloons first, then reads each one on its own.
 *
 * This is the point of carrying a detector. Grouping recognised lines by
 * position was attempted twice and reverted twice; a model trained on comics
 * answers the question directly, and one balloon then becomes one unit of
 * translation — which measurement showed is what a capable translator needs and
 * what splitting denies it (`docs/milestones/v2.md`).
 *
 * Reading each balloon separately also spares the recogniser the rest of the
 * page: no sound effects, no cover lettering, no browser chrome.
 */
@Singleton
internal class DetectingPageReader @Inject constructor(
    private val detector: TextDetector,
    // Deliberately the bubble recogniser rather than the injected
    // [TextRecognizer]: this is the only path that hands over a cropped balloon,
    // which is the only input manga-ocr can read.
    private val recognizer: BubbleRecognizer,
    private val fallback: GroupingPageReader,
    private val logger: BabelLogger,
) : PageReader {

    private val grouper = TextRegionGrouper()

    override suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Unit) {
        // Unavailable used to be the normal state and is now the broken one:
        // the detector ships with the app (ADR 011), so this is false only when
        // a load has failed. The older path still works, which is what makes
        // that survivable. Finding *zero* balloons remains a different thing: a
        // page with no dialogue genuinely has none, and falling back there
        // would put the sound effects and the chrome straight back.
        if (!detector.isAvailable) return fallback.read(frame, onRegion)

        val bubbles = detector.detect(frame)
        logger.debug(TAG, "detector found ${bubbles.size} bubbles")

        // Asked again, because availability is only fully known after trying:
        // the model ships with the app now, so the answer above is yes until a
        // load actually fails. Without this the first page after such a failure
        // renders nothing at all before the check above starts catching it.
        if (bubbles.isEmpty() && !detector.isAvailable) return fallback.read(frame, onRegion)

        // One at a time, and that is a measured choice rather than the obvious
        // one. Reading two balloons concurrently is genuinely faster — 3.3s to
        // 2.4s on a page of eight — but each concurrent read holds its own
        // activations, and the peak went from 494MB to **726MB**. Capping ONNX
        // Runtime to one thread per session brought that back only to 652MB and
        // gave up most of the speed (3.0s).
        //
        // 150–230MB for 0.3–0.9s is a bad trade in a pipeline already large
        // enough to be worth killing. The decoder has no key/value cache, and
        // an export carrying one is the lever that costs no memory.
        //
        // Handed over one at a time as well, which is what makes the serial
        // read bearable: the reader waits for every balloon, but the *screen*
        // no longer does.
        for (bubble in bubbles) {
            read(frame, bubble)?.let { onRegion(it) }
        }
    }

    override suspend fun release() {
        detector.release()
        recognizer.release()
    }

    private suspend fun read(frame: Bitmap, bubble: DetectedBubble): TextRegion? {
        val crop = frame.cropTo(bubble.text) ?: return null
        val lines = try {
            recognizer.recognize(crop)
        } finally {
            crop.recycle()
        }
        if (lines.isEmpty()) return null

        // The grouper still answers two questions here — which way the text is
        // set, and what order the columns read in. Both are tested, and vertical
        // Japanese is not something to get wrong in a second place.
        val grouped = grouper.group(lines)
        if (grouped.isEmpty()) return null

        // But the *unit* is the balloon, whatever the grouper makes of the
        // positions. It reasons from where lines sit; the detector reasons from
        // what a balloon looks like, and on that question the detector is the
        // better witness — which is the whole reason for carrying it.
        val orientation = grouped.first().orientation
        val ordered = grouped.sortedWith(readingOrder(orientation)).flatMap { it.lines }

        // Back to the frame's coordinates. Everything downstream — placement,
        // the privacy policy's per-app rules, the renderer — works in screen
        // space and knows nothing about a crop.
        return TextRegion(
            lines = ordered.map { line ->
                line.copy(bounds = line.bounds.movedBy(bubble.text.left, bubble.text.top))
            },
            // The balloon, when the detector found one. This replaces growing a
            // rectangle out from the text until the pixels stop matching — a
            // heuristic that leaked through balloon tails and was reverted once
            // already.
            // Still the lettering: the balloon is a limit on growing it, not a
            // substitute for it. See [TextRegion.enclosure].
            bounds = lineUnion(ordered, bubble),
            orientation = orientation,
            enclosure = bubble.balloon,
        )
    }

    /**
     * Where the lettering actually is, in frame coordinates.
     *
     * The detector's text box is a little generous; the recognised lines are
     * tighter, and tighter is what the colour sample and the growth both want to
     * start from. Falls back to the detector's box when the lines give nothing.
     */
    private fun lineUnion(lines: List<RecognizedLine>, bubble: DetectedBubble): TextBounds {
        val dx = bubble.text.left
        val dy = bubble.text.top
        val left = lines.minOfOrNull { it.bounds.left } ?: return bubble.text
        return TextBounds(
            left = left + dx,
            top = (lines.minOf { it.bounds.top }) + dy,
            right = (lines.maxOf { it.bounds.right }) + dx,
            bottom = (lines.maxOf { it.bounds.bottom }) + dy,
            space = bubble.text.space,
        )
    }

    /** Vertical Japanese reads right to left; anything else reads downwards. */
    private fun readingOrder(orientation: TextOrientation): Comparator<TextRegion> =
        if (orientation == TextOrientation.VERTICAL) {
            compareByDescending<TextRegion> { it.bounds.right }.thenBy { it.bounds.top }
        } else {
            compareBy<TextRegion> { it.bounds.top }.thenBy { it.bounds.left }
        }

    private fun Bitmap.cropTo(bounds: TextBounds): Bitmap? {
        val left = bounds.left.coerceIn(0, width)
        val top = bounds.top.coerceIn(0, height)
        val right = bounds.right.coerceIn(left, width)
        val bottom = bounds.bottom.coerceIn(top, height)
        if (right - left < MIN_SIDE || bottom - top < MIN_SIDE) return null
        return Bitmap.createBitmap(this, left, top, right - left, bottom - top)
    }

    private fun TextBounds.movedBy(dx: Int, dy: Int) =
        copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)

    private companion object {
        const val TAG = "DetectingPageReader"
        const val MIN_SIDE = 8
    }
}
