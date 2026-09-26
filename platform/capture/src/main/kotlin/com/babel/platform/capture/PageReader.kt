package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.common.BabelLogger
import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation
import com.babel.domain.vision.ClippedBalloons
import com.babel.domain.vision.LineJoin
import com.babel.domain.vision.MangaReadingOrder
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.ScrolledBalloons
import com.babel.domain.vision.SoundEffect
import com.babel.domain.vision.TextRegion
import com.babel.domain.vision.TextRegionGrouper
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

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
     * [onRegion] is called on the reading coroutine, in reading order, and
     * answers whether the rest of the page is still wanted. **False stops the
     * read.**
     *
     * That is not a nicety. A scroll landing mid-read means the screen being
     * read is gone, and measured on a device the reader carried on anyway:
     * 1997ms spent finishing a page whose every region was then dropped, while
     * the screen the user was actually looking at waited its turn. Nothing read
     * before the stop is wasted — it is remembered, which is why the re-read
     * that follows costs a fraction of the first.
     */
    suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Boolean)

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
    override suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Boolean) {
        val lines = recognizer.recognize(frame)
        if (lines.isEmpty()) return
        for (region in grouper.group(lines)) {
            // The recognition is already paid for on this path, so stopping
            // saves nothing here. It is still honoured: a caller that has said
            // it no longer wants the page should not keep being handed it.
            if (!onRegion(region)) return
        }
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

    /**
     * What has been read of this page, in **page** coordinates.
     *
     * Not just the previous screen. Each frame solves for its own absolute
     * offset against all of these at once, which buys two things: scrolling
     * back to a balloon already read costs nothing, and no drift accumulates,
     * because every frame is measured against the original coordinates rather
     * than against the frame before it.
     *
     * Only ever touched from the reading coroutine, which the scan loop runs
     * one at a time; volatile because [release] can arrive from another.
     */
    @Volatile
    private var pageMemory: List<ReadBalloon> = emptyList()

    /**
     * A balloon already read, kept against the chance that it comes back.
     *
     * [box] is in page coordinates: the screen box plus the offset of the frame
     * it was read on.
     */
    private data class ReadBalloon(val box: TextBounds, val lines: List<RecognizedLine>)

    override suspend fun read(frame: Bitmap, onRegion: suspend (TextRegion) -> Boolean) {
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
        //
        // In reading order, which the detector does not give: it returns
        // balloons in whatever order the model emits them. That was invisible
        // while each balloon was its own sentence, and is what "join this
        // balloon to the next" needs to mean anything (`MangaReadingOrder`).
        // It also puts the incremental publishing in the order the page is
        // read, which is the order a reader wants it to appear in.
        val ordered = bubbles.sortedWith(readingOrder(frame))

        // How far the page moved since the last read, when it moved at all.
        //
        // Scrolling a webtoon used to read every balloon on the new screen from
        // scratch, including the ones that were fully read a moment earlier:
        // 4.3s of recognition and eight provider calls per swipe, measured on a
        // device. The translation cache could not help, because it is keyed on
        // the text and a balloon read twice comes back slightly different — 8
        // of 10 missed.
        //
        // Null for a page that changed rather than moved, and then everything
        // below reads as it always did (`ScrolledBalloons`).
        val shift = ScrolledBalloons.shiftBetween(pageMemory.map { it.box }, ordered.map { it.text })

        // Where this screen sits on the page. Solved against the whole memory
        // rather than accumulated from the frame before, so it cannot drift.
        val offset = shift ?: 0

        // The chain from here back to the frames the memory was built on is
        // broken, so the memory is thrown away rather than carried.
        //
        // This is the whole of the zoom defence, and it is deliberately not a
        // zoom detector. A zoom changes every width and height - 3% of a 300px
        // balloon is already past the 8px tolerance - so no offset can be
        // recovered, and this fires. Keeping the entries instead would leave a
        // set of page coordinates measured at the old scale; zoom back to it
        // later and one of them could match again at an offset that is no
        // longer true, which is how a balloon ends up with somebody else's
        // words in it. The same reasoning covers a page turn, an app switch and
        // a rotation, none of which needs a case of its own.
        if (shift == null && pageMemory.isNotEmpty()) {
            logger.debug(TAG, "lost the page; forgetting ${pageMemory.size} readings")
            pageMemory = emptyList()
        }

        // Balloons the viewport has cut in half are left for the screen that
        // shows them whole. Half a balloon costs a recognition and a provider
        // call to produce half a sentence, and a cut box cannot be matched
        // after the next scroll either, so it is paid for again
        // (`ClippedBalloons`).
        //
        // Only on a screen this can place, though. Without a shift there is no
        // knowing where this screen came from — the first one after manga mode
        // goes on, a page turn, an app switch — and a balloon at the top of a
        // page genuinely *is* whole, so skipping it would mean never reading
        // it. Unplaceable screens therefore behave exactly as they did before
        // any of this, which makes the rule incapable of being worse.
        //
        // Cut boxes still vote above: they are evidence of where the screen
        // moved to even when they are not worth reading.
        val readable = if (shift == null) ordered else {
            ordered.filterNot { ClippedBalloons.waitsForMore(it.text, frame.height) }
        }
        if (readable.size < ordered.size) {
            logger.debug(TAG, "left ${ordered.size - readable.size} cut balloons for the next screen")
        }

        val thisPage = ArrayList<ReadBalloon>(readable.size)
        var reused = 0
        var stopped = false
        for (bubble in readable) {
            val remembered = shift?.let { moved ->
                pageMemory.firstOrNull { ScrolledBalloons.isSame(it.box, bubble.text, moved) }
            }
            val lines = if (remembered != null) {
                reused += 1
                remembered.lines
            } else {
                recognize(frame, bubble)
            }
            if (lines.isEmpty()) continue

            // Stored where it sits on the page, not on this screen, so one
            // entry answers for every later frame that shows it.
            thisPage += ReadBalloon(bubble.text.movedBy(0, offset), lines)

            // Assembled from this frame's detection either way. Only the
            // reading is reused; the balloon outline, the placement and the
            // sound-effect test all come from what is on screen now.
            val region = assemble(bubble, lines)
            if (region != null && !onRegion(region)) {
                // The screen this was being read from is gone. What has been
                // read is still remembered below — it is the same page, and the
                // scan that replaces this one will reuse it.
                logger.debug(TAG, "stopped after $reused reused of ${readable.size}")
                stopped = true
                break
            }
        }
        remember(thisPage, offset, frame.height)

        if (shift != null && !stopped) {
            logger.debug(
                TAG,
                "page at ${offset}px; reused $reused of ${readable.size}, " +
                    "${pageMemory.size} readings held",
            )
        }
    }

    /**
     * Folds this screen into the page memory and drops what is out of reach.
     *
     * An entry the current screen supersedes is replaced rather than
     * duplicated: one balloon read again, or carried again, is one balloon.
     * What is kept is everything within [REACH] screens of the viewport, which
     * is what makes scrolling back free without letting the memory grow with
     * the chapter - and a smaller memory is a safer one too, since every extra
     * entry is another chance for two boxes to agree by accident.
     */
    private fun remember(current: List<ReadBalloon>, offset: Int, frameHeight: Int) {
        val top = offset - REACH * frameHeight
        val bottom = offset + frameHeight + REACH * frameHeight
        val kept = pageMemory.filter { old ->
            old.box.bottom > top && old.box.top < bottom &&
                current.none { ScrolledBalloons.isSame(old.box, it.box, 0) }
        }

        val all = kept + current
        pageMemory = if (all.size <= LIMIT) all else {
            // A backstop only; [REACH] is what normally bounds this. Furthest
            // from the screen goes first, being the least likely to be
            // scrolled back to.
            val centre = offset + frameHeight / 2
            all.sortedBy { abs((it.box.top + it.box.bottom) / 2 - centre) }.take(LIMIT)
        }
    }

    override suspend fun release() {
        // The remembered readings go with them. Manga mode coming back on is a
        // new screen, and the alternative is reusing a reading of whatever was
        // in front of the user before they switched it off.
        pageMemory = emptyList()
        detector.release()
        recognizer.release()
    }

    private suspend fun recognize(frame: Bitmap, bubble: DetectedBubble): List<RecognizedLine> {
        val crop = frame.cropTo(bubble.text) ?: return emptyList()
        return try {
            recognizer.recognize(crop)
        } finally {
            crop.recycle()
        }
    }

    private fun assemble(bubble: DetectedBubble, lines: List<RecognizedLine>): TextRegion? {
        // The one place free text is told apart from speech, and it has to be
        // here because the test is on the words rather than on the box. A
        // short katakana read inside a balloon is somebody speaking; the same
        // read on the artwork is a noise drawn into the picture.
        if (bubble.onArt && SoundEffect.isDrawnNoise(LineJoin.join(lines.map { it.text }))) {
            logger.debug(TAG, "dropped a sound effect")
            return null
        }

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

    /** Balloons across the page, as opposed to columns within one. */
    private fun readingOrder(frame: Bitmap): Comparator<DetectedBubble> {
        val across = MangaReadingOrder.of(frame.height)
        return Comparator { first, second -> across.compare(first.text, second.text) }
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

        /**
         * How many screens either side of the viewport stay in memory.
         *
         * Two is a few flicks back, which is as far as anybody scrolls to
         * re-read something. Beyond that a reading is cheaper to redo than to
         * carry, and carrying it only adds pairs that could agree by accident.
         */
        const val REACH = 2

        /** A hard cap, for a page denser than any of the sample material. */
        const val LIMIT = 120
    }
}
