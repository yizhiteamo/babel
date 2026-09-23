package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.common.BabelLogger
import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextShare
import com.babel.core.model.TextOrientation
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextElementIds
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.vision.BubbleBounds
import com.babel.domain.vision.FrameChangeDetector
import com.babel.domain.vision.ImageTextScanner
import com.babel.domain.vision.JapaneseClause
import com.babel.domain.vision.OcrPunctuation
import com.babel.domain.vision.TextRegion
import com.babel.platform.screen.ScreenFrameSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns captured frames into the same events the pipeline already consumes.
 *
 * Everything downstream — coordination, caching, stale-result rejection,
 * rendering — is reused untouched. That is the point of normalising every
 * source into `TextElement` before it enters the pipeline (ADR 004): adding OCR
 * required no change to the translation core.
 */
@Singleton
class CaptureTextSource @Inject internal constructor(
    private val frames: ScreenFrameSource,
    private val pages: PageReader,
    private val recognizer: TextRecognizer,
    private val logger: BabelLogger,
) : ImageTextScanner {

    override val sourceType: TextSourceType = TextSourceType.OCR

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 64)

    private val lock = Mutex()
    private var previousIds: Set<TextElementId> = emptySet()
    private var generation = 0L

    /**
     * How many times [clear] has run, so a scan can tell whether the screen it
     * was reading went away while it read it.
     *
     * Recognition takes seconds, and a user changing pages does not wait for
     * it. Without this, a clear issued mid-scan is undone by the scan that
     * caused it: the results arrive afterwards and put the old screen's
     * translations back, where nothing is left to take them down again.
     */
    private var clears = 0L

    /** Signature of the last frame actually recognised; null before the first. */
    private var lastRecognized: IntArray? = null

    /**
     * Signature of the last frame *seen*, recognised or not, so a screen still
     * being painted can be told from one that has come to rest.
     */
    private var lastSeen: IntArray? = null

    override fun events(): Flow<TextSourceEvent> = events.asSharedFlow()

    /**
     * Reads one frame and publishes what changed since the last one.
     *
     * The frame is recycled before returning: a screen capture is the largest
     * object this code touches, and holding one per scan would be the easiest
     * way to run the process out of memory.
     */
    override suspend fun scanOnce(
        packageName: String?,
        exclusions: List<TextBounds>,
        within: TextBounds?,
    ) {
        var startedAfter = lock.withLock { clears }
        val captureStarted = System.currentTimeMillis()

        // Wait for the screen to stop moving, checking often.
        //
        // A frame read mid-transition puts the outgoing screen's text across
        // the incoming one, so settling is not optional. But settling is
        // decided by comparing two frames, and sampling once per 1.5s tick made
        // the *first* page after any change cost a whole extra tick — half of a
        // measured 3.0s wait. Re-checking here instead costs one short delay.
        //
        // [SETTLE_RECHECK_MS] clears the platform's screenshot throttle of
        // roughly one per 333ms; below it the capture simply fails and the
        // re-check learns nothing.
        var frame: Bitmap? = null
        var signature: IntArray? = null
        for (attempt in 0 until SETTLE_ATTEMPTS) {
            if (attempt > 0) delay(SETTLE_RECHECK_MS)

            val candidate = frames.latestFrame() ?: return
            val candidateSignature = FrameSignature.of(candidate)
            val settled = FrameChangeDetector.hasSettled(lastSeen, candidateSignature)
            lastSeen = candidateSignature

            if (settled) {
                frame = candidate
                signature = candidateSignature
                break
            }
            // Still moving. The bitmap is the largest object here, so it goes
            // now rather than at the end of the loop.
            candidate.recycle()
        }

        // Never came to rest — an animation, a video, a page still loading.
        // Leave it to the next tick rather than reading a smear.
        if (frame == null || signature == null) {
            logger.debug(TAG, "skipped: the screen never came to rest")
            return
        }
        val captureMs = System.currentTimeMillis() - captureStarted

        // A different page from the one already read? Re-recognising the same
        // page would replace its translations with a slightly different reading
        // of it.
        if (!FrameChangeDetector.shouldRecognize(lastRecognized, signature)) {
            logger.debug(TAG, "skipped: the same page as last read")
            frame.recycle()
            return
        }

        // Take our own translations off the screen before reading it.
        //
        // The frame comes from the live display, so it contains whatever Babel
        // has already drawn — and a translation is a light rounded box with
        // lettering in it, which is exactly the shape a comic balloon detector
        // is trained to find. Measured: drawing two translations on a page
        // takes the detector from eight balloons to nine
        // (`DetectorReadsOwnOverlaysTest`). On a device that put the previous
        // page's Chinese back onto the new page, where no later scan could
        // remove it — the page was not changing any more.
        //
        // [FrameChangeDetector] cannot cover this. It stops an *unchanged* page
        // being read twice, and this is the one frame where the page has
        // changed, which is the only kind of frame that ever gets read.
        //
        // The cost is one screenshot interval per page turn. The alternative
        // considered was excluding the regions our translations occupy, which
        // costs nothing — but a real balloon landing where an old translation
        // was would be dropped silently, and with the page now static nothing
        // would come back for it.
        if (lock.withLock { previousIds.isNotEmpty() }) {
            clear()
            // The renderer takes the windows down on the main thread, and a
            // capture asked for sooner than this simply fails anyway: the
            // platform allows roughly one screenshot per 333ms.
            delay(SETTLE_RECHECK_MS)

            frame.recycle()
            frame = frames.latestFrame()
            signature = frame?.let { FrameSignature.of(it) }
            if (frame == null || signature == null) {
                logger.debug(TAG, "skipped: no frame after taking our own translations down")
                return
            }
            // Our own clear moved the counter the drop guard compares against.
            startedAfter = lock.withLock { clears }
        }

        // Read only the app's content area.
        //
        // Regions outside it are discarded a few lines below — they are the
        // app's chrome, not artwork — so recognising them is work whose result
        // goes straight in the bin. Measured on a comic page in a browser: 14 of
        // 18 regions were thrown away, and recognition is the largest single
        // cost in the pipeline.
        //
        // This can lose nothing that the filter would not have dropped anyway,
        // which is what makes it safe to do before reading rather than after.
        // The narrower frame also keeps [BubbleBounds] from growing a bubble up
        // into the toolbar, and lowers the peak memory of doubling the frame.
        val crop = within?.clippedTo(frame)
        val page = crop?.let {
            Bitmap.createBitmap(frame, it.left, it.top, it.width, it.height)
        } ?: frame
        val dx = crop?.left ?: 0
        val dy = crop?.top ?: 0
        val exclusionsInPage = if (crop == null) exclusions else exclusions.map { it.movedBy(-dx, -dy) }

        // Published as each balloon is read, not when the page is done.
        //
        // Measured across eight real pages: reading costs 1.1s to 4.9s, and the
        // slowest had only three balloons — the decoder has no key/value cache,
        // so one long balloon can outweigh five short ones. Waiting for the
        // whole page meant every translation waited for the worst balloon on
        // it. The first one now reaches the screen in about a second and a half.
        //
        // The identity bookkeeping has to move with it. `occurrences` numbers
        // repeated text within a page, so it spans the whole read rather than
        // one region, and `generation` is bumped once for the page.
        val readStarted = System.currentTimeMillis()
        val occurrences = mutableMapOf<String, Int>()
        val currentIds = LinkedHashSet<TextElementId>()
        var read = 0
        var onInterface = 0
        var abandoned = false
        generation += 1

        // A sentence cut across balloons has to be translated whole, so a
        // balloon that cannot stand on its own waits for the one after it
        // (`JapaneseClause`). Everything else still goes out the moment it is
        // read — which is nearly every balloon on nearly every page, so the
        // incremental publishing this replaced is kept for all but the groups.
        val group = mutableListOf<TextRegion>()

        val flush: suspend () -> Boolean = flush@{
            if (group.isEmpty()) return@flush true

            // Placed first, because the weights that divide the translation are
            // the drawn boxes' areas rather than the lettering's.
            val joined = group.joinToString("") { OcrPunctuation.normalize(it.text) }
            val placed = group.map { region ->
                toElement(region, joined, packageName, occurrences) { bounds, set, enclosure ->
                    val placement = placeIn(page, bounds, set, enclosure)
                    // Back to screen coordinates, which is the only space
                    // anything downstream knows about.
                    placement.copy(bounds = placement.bounds.movedBy(dx, dy))
                }
            }
            val weights = placed.map { it.bounds.width * it.bounds.height }

            var published = true
            for ((index, element) in placed.withIndex()) {
                // A group of one is the ordinary case and carries no share:
                // its whole translation is its own.
                val shared = if (placed.size == 1) element
                else element.copy(share = TextShare(index = index, weights = weights))
                currentIds += shared.id
                if (!publishOne(shared, startedAfter)) {
                    published = false
                    break
                }
            }
            group.clear()
            published
        }

        try {
            pages.read(page) { region ->
                read += 1
                // Cleared while this page was being read — the user has moved
                // on. Stop publishing; the rest of the reading is already paid
                // for but none of it belongs on the screen.
                if (abandoned) return@read

                // Already guaranteed when the frame was cropped to it.
                val outside = crop == null && within != null && !region.bounds.centreIsIn(within)
                if (outside || region.bounds.isExcludedBy(exclusionsInPage)) {
                    onInterface += 1
                    return@read
                }

                group += region
                // Held only for balloons. Lettering on the artwork is scattered
                // across panels where reading order is a much weaker claim, and
                // page 05's `私はたった今から` really does continue — two
                // regions later, not in the next one.
                val waits = region.enclosure != null &&
                    group.size < MAX_GROUP &&
                    JapaneseClause.isUnfinished(OcrPunctuation.normalize(region.text))
                if (waits) return@read

                // Both done before the frame goes: this is the only moment the
                // pixels behind the text exist. Accessibility never had them,
                // which is why V1 overlays could only guess at a background.
                if (!flush()) abandoned = true
            }

            // A page can end on an unfinished balloon — the sentence carries
            // into the next page, which this pipeline never sees. It goes out
            // on its own rather than being lost.
            if (!abandoned && !flush()) abandoned = true
        } finally {
            if (page !== frame) page.recycle()
            frame.recycle()
        }

        // Counts and durations only — recognised text is screen content and
        // stays out of diagnostics (`docs/systems/privacy.md`).
        //
        // Timed because a user called manga mode slow and there was no figure to
        // answer with. Reading is no longer separable from placing now that they
        // interleave, so they are reported together.
        logger.debug(
            TAG,
            "read $read regions, $onInterface on interface" +
                (if (abandoned) ", abandoned part way" else "") +
                " (capture ${captureMs}ms, read and place " +
                "${System.currentTimeMillis() - readStarted}ms)",
        )

        if (!abandoned) finishPublishing(currentIds, startedAfter, signature)
    }

    override suspend fun release() {
        clear()
        pages.release()
    }

    /** Drops everything currently tracked, e.g. when the session ends. */
    override suspend fun clear() {
        lock.withLock {
            clears += 1
            previousIds = emptySet()
            lastRecognized = null
            lastSeen = null
        }
        events.emit(TextSourceEvent.Cleared(sourceType))
    }

    /** Whether this region sits on something the text path already sees. */
    private fun TextBounds.isExcludedBy(exclusions: List<TextBounds>): Boolean =
        exclusions.any { centreIsIn(it) }

    private fun TextBounds.movedBy(dx: Int, dy: Int): TextBounds =
        if (dx == 0 && dy == 0) this
        else copy(left = left + dx, top = top + dy, right = right + dx, bottom = bottom + dy)

    /**
     * This rectangle trimmed to what the frame actually contains, or null when
     * cropping to it would not be worth it.
     *
     * Null rather than an exception for the awkward cases — a content area
     * reported larger than the display, or one so nearly the whole frame that
     * copying the bitmap costs more than the recognition it saves. The caller
     * then reads the whole frame, which is what it did before this existed.
     */
    private fun TextBounds.clippedTo(frame: Bitmap): TextBounds? {
        val clipped = copy(
            left = left.coerceIn(0, frame.width),
            top = top.coerceIn(0, frame.height),
            right = right.coerceIn(0, frame.width),
            bottom = bottom.coerceIn(0, frame.height),
        )
        if (clipped.width < MIN_CROP || clipped.height < MIN_CROP) return null

        val frameArea = frame.width.toLong() * frame.height
        val cropArea = clipped.width.toLong() * clipped.height
        return clipped.takeIf { cropArea <= frameArea * CROP_WORTH_IT_PERCENT / 100 }
    }

    /**
     * Works out where a translation should go and what colour it should be.
     *
     * The recognised box hugs the lettering, and for vertical Japanese that is
     * a narrow column — laying a horizontal translation into it gives two or
     * three characters a line. The bubble around the text is the space actually
     * available, so the box is grown into it first.
     *
     * The colour is sampled from the text's own box rather than the grown one:
     * it is the tighter, more certain sample, and it is what defines the
     * background that the growing then follows.
     */
    private fun placeIn(
        frame: Bitmap,
        text: TextBounds,
        set: TextOrientation,
        enclosure: TextBounds?,
    ): Placement {
        // How the source was set travels with how it looked: a renderer given
        // vertical dialogue can set the translation vertically too, which is
        // both how lettering looks and how a translation comes to cover the
        // text it replaces (`docs/milestones/v2.md`).
        val style = FrameSampler.sample(frame, text).copy(orientation = set)
        val background = style.backgroundColor ?: return Placement(text, style)

        val bubble = BubbleBounds.expand(
            start = text,
            // The detected balloon when there is one. Growing to the whole frame
            // is what let the old version leak out through a balloon's tail; a
            // limit the detector supplies stops that without giving up the
            // inscribed shape that growing produces.
            limit = enclosure ?: FrameSampler.frameBounds(frame),
            isBackground = FrameSampler.backgroundTest(frame, background),
        )
        return Placement(bubble, style)
    }

    private data class Placement(val bounds: TextBounds, val style: SourceStyle)

    /**
     * One region, numbered against the page it belongs to.
     *
     * [occurrences] is the caller's, and spans the whole page: two balloons
     * saying the same thing have to get different ids, and that is only
     * decidable across the page rather than within one region.
     */
    /**
     * @param text what this element is translated as, already repaired. It is
     *   the region's own words for a lone balloon and the group's joined line
     *   for a shared one — which is what makes every balloon in a group agree
     *   on a cache key, so the group costs one call rather than several.
     */
    private fun toElement(
        region: TextRegion,
        text: String,
        packageName: String?,
        occurrences: MutableMap<String, Int>,
        place: (TextBounds, TextOrientation, TextBounds?) -> Placement,
    ): TextElement {
        // Repair happens in the caller, once, where the recogniser's mistakes
        // are made: `......` is not how anybody writes `……`, and a provider
        // given the raw form returns the dots without the words. Doing it
        // before the id is derived also keeps the id stable across the repair
        // (`docs/systems/text-model.md`).
        val index = occurrences.getOrDefault(text, 0)
        occurrences[text] = index + 1
        val placement = place(region.bounds, region.orientation, region.enclosure)

        return TextElement(
            id = TextElementIds.forContent(
                // OCR has no window to scope by; the source is the screen
                // itself, so a constant keeps ids stable across frames
                // while content decides identity.
                scope = SCOPE,
                text = text,
                occurrence = index,
            ),
            text = text,
            bounds = placement.bounds,
            sourceType = TextSourceType.OCR,
            // Carried so the privacy policy's per-app exclusions apply
            // here as they do on the node path. There is no window id: a
            // capture is of the screen, not of a window.
            source = SourceIdentity(packageName = packageName),
            revision = Revision(generation),
            style = placement.style,
            // Told rather than guessed where the recogniser can vouch for
            // it, and left to detection where it cannot.
            sourceLanguage = recognizer.languageOf(text),
        )
    }

    /**
     * Puts one balloon on screen, unless the screen it came from is gone.
     *
     * @return false when the page was cleared mid-read, which is the caller's
     *   signal to stop publishing the rest of it.
     */
    private suspend fun publishOne(element: TextElement, startedAfter: Long): Boolean {
        val stillHere = lock.withLock { clears == startedAfter }
        if (!stillHere) {
            // Cleared while this scan ran, so it is a reading of a screen the
            // user has already left. Publishing it would undo the clear.
            logger.debug(TAG, "dropped a scan of a screen that is gone")
            return false
        }
        events.emit(TextSourceEvent.Upserted(listOf(element)))
        return true
    }

    /**
     * Closes the page: what went away, and the fact that it was read.
     *
     * Both have to wait for the whole page. Removals need the complete set of
     * ids to diff against, and [lastRecognized] is deliberately recorded here
     * rather than before the reading — marking the page read and *then*
     * throwing the reading away left the page looking done, so it was never
     * read again. Measured as a comic that stayed untranslated until it was
     * scrolled.
     */
    private suspend fun finishPublishing(
        currentIds: Set<TextElementId>,
        startedAfter: Long,
        signature: IntArray,
    ) {
        val removed = lock.withLock {
            if (clears != startedAfter) {
                logger.debug(TAG, "dropped a scan of a screen that is gone")
                return
            }
            lastRecognized = signature
            val gone = previousIds - currentIds
            previousIds = currentIds
            gone
        }

        if (removed.isNotEmpty()) events.emit(TextSourceEvent.Removed(removed.toList()))
    }

    private companion object {
        const val TAG = "CaptureTextSource"

        /**
         * The most balloons one sentence may be joined across.
         *
         * Three, because a chain has to end somewhere and a misread ending in a
         * particle would otherwise swallow the rest of the page. The measured
         * splits were all two; three leaves room for one more without letting a
         * mistake run.
         */
        const val MAX_GROUP = 3

        /**
         * How long to wait before asking again whether the screen has stopped
         * moving. Above the platform's screenshot throttle of roughly one per
         * 333ms, so the re-check gets a frame rather than a failure.
         */
        const val SETTLE_RECHECK_MS = 350L

        /**
         * How many times to ask before giving up and leaving it to the next
         * tick. Three bounds the wait at about 0.7s, which is shorter than the
         * 1.5s tick it replaces — a screen that is still moving after that is
         * animating, not loading.
         */
        const val SETTLE_ATTEMPTS = 3

        /** Below this a "content area" is a misreading, not a page. */
        const val MIN_CROP = 64

        /**
         * Only crop when it removes enough to pay for copying the bitmap. A
         * browser's tab strip and address bar come to well under this; a
         * full-screen reader exposes a content area that is nearly the whole
         * display and is left alone.
         */
        const val CROP_WORTH_IT_PERCENT = 95
        const val SCOPE = "ocr"
    }
}
