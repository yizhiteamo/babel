package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.common.BabelLogger
import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextElementIds
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.vision.BubbleBounds
import com.babel.domain.vision.FrameChangeDetector
import com.babel.domain.vision.ImageTextScanner
import com.babel.domain.vision.TextRegion
import com.babel.domain.vision.TextRegionGrouper
import com.babel.platform.screen.ScreenFrameSource
import javax.inject.Inject
import javax.inject.Singleton
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
    private val recognizer: TextRecognizer,
    private val logger: BabelLogger,
) : ImageTextScanner {

    override val sourceType: TextSourceType = TextSourceType.OCR

    private val grouper = TextRegionGrouper()

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 64)

    private val lock = Mutex()
    private var previousIds: Set<TextElementId> = emptySet()
    private var generation = 0L

    /** Signature of the last frame actually recognised; null before the first. */
    private var lastRecognized: IntArray? = null

    override fun events(): Flow<TextSourceEvent> = events.asSharedFlow()

    /**
     * Reads one frame and publishes what changed since the last one.
     *
     * The frame is recycled before returning: a screen capture is the largest
     * object this code touches, and holding one per scan would be the easiest
     * way to run the process out of memory.
     */
    override suspend fun scanOnce(packageName: String?) {
        val frame = frames.latestFrame() ?: return

        val signature = FrameSignature.of(frame)
        if (!FrameChangeDetector.shouldRecognize(lastRecognized, signature)) {
            // Same page as last time. Returning without publishing leaves the
            // existing translations in place — re-recognising would replace
            // them with a slightly different reading of the same page.
            frame.recycle()
            return
        }
        lastRecognized = signature

        val elements = try {
            val lines = recognizer.recognize(frame)
            if (lines.isEmpty()) {
                emptyList()
            } else {
                val regions = grouper.group(lines)
                // Counts only — recognised text is screen content and stays out
                // of diagnostics (`docs/systems/privacy.md`).
                logger.debug(TAG, "recognised ${lines.size} lines in ${regions.size} regions")

                // Both done before the frame goes: this is the only moment the
                // pixels behind the text exist. Accessibility never had them,
                // which is why V1 overlays could only guess at a background.
                toElements(regions, packageName) { bounds -> placeIn(frame, bounds) }
            }
        } finally {
            frame.recycle()
        }

        publish(elements)
    }

    /** Drops everything currently tracked, e.g. when the session ends. */
    override suspend fun clear() {
        lock.withLock {
            previousIds = emptySet()
            lastRecognized = null
        }
        events.emit(TextSourceEvent.Cleared)
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
    private fun placeIn(frame: Bitmap, text: TextBounds): Placement {
        val style = FrameSampler.sample(frame, text)
        val background = style.backgroundColor ?: return Placement(text, style)

        val bubble = BubbleBounds.expand(
            start = text,
            limit = FrameSampler.frameBounds(frame),
            isBackground = FrameSampler.backgroundTest(frame, background),
        )
        return Placement(bubble, style)
    }

    private data class Placement(val bounds: TextBounds, val style: SourceStyle)

    private fun toElements(
        regions: List<TextRegion>,
        packageName: String?,
        place: (TextBounds) -> Placement,
    ): List<TextElement> {
        val occurrences = mutableMapOf<String, Int>()
        generation += 1

        return regions.map { region ->
            val index = occurrences.getOrDefault(region.text, 0)
            occurrences[region.text] = index + 1
            val placement = place(region.bounds)

            TextElement(
                id = TextElementIds.forContent(
                    // OCR has no window to scope by; the source is the screen
                    // itself, so a constant keeps ids stable across frames
                    // while content decides identity.
                    scope = SCOPE,
                    text = region.text,
                    occurrence = index,
                ),
                text = region.text,
                bounds = placement.bounds,
                sourceType = TextSourceType.OCR,
                // Carried so the privacy policy's per-app exclusions apply
                // here as they do on the node path. There is no window id: a
                // capture is of the screen, not of a window.
                source = SourceIdentity(packageName = packageName),
                revision = Revision(generation),
                style = placement.style,
            )
        }
    }

    /** Same diffing as the accessibility source: only changes go downstream. */
    private suspend fun publish(elements: List<TextElement>) {
        val (removed, upserted) = lock.withLock {
            val currentIds = elements.mapTo(LinkedHashSet()) { it.id }
            val gone = previousIds - currentIds
            previousIds = currentIds
            gone to elements
        }

        if (removed.isNotEmpty()) events.emit(TextSourceEvent.Removed(removed.toList()))
        if (upserted.isNotEmpty()) events.emit(TextSourceEvent.Upserted(upserted))
    }

    private companion object {
        const val TAG = "CaptureTextSource"
        const val SCOPE = "ocr"
    }
}
