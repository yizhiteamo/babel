package com.babel.platform.capture

import com.babel.core.common.BabelLogger
import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextElementIds
import com.babel.domain.acquisition.TextSource
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.vision.TextRegion
import com.babel.domain.vision.TextRegionGrouper
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
    private val capture: MediaProjectionScreenCapture,
    private val recognizer: TextRecognizer,
    private val logger: BabelLogger,
) : TextSource {

    override val sourceType: TextSourceType = TextSourceType.OCR

    private val grouper = TextRegionGrouper()

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 64)

    private val lock = Mutex()
    private var previousIds: Set<TextElementId> = emptySet()
    private var generation = 0L

    override fun events(): Flow<TextSourceEvent> = events.asSharedFlow()

    /**
     * Reads one frame and publishes what changed since the last one.
     *
     * The frame is recycled before returning: a screen capture is the largest
     * object this code touches, and holding one per scan would be the easiest
     * way to run the process out of memory.
     */
    suspend fun scanOnce() {
        val frame = capture.latestFrame() ?: return

        val lines = try {
            recognizer.recognize(frame)
        } finally {
            frame.recycle()
        }

        if (lines.isEmpty()) {
            publish(emptyList())
            return
        }

        val regions = grouper.group(lines)
        // Counts and sizes only — recognised text is screen content and stays
        // out of diagnostics (`docs/systems/privacy.md`).
        logger.debug(TAG, "recognised ${lines.size} lines in ${regions.size} regions")

        publish(toElements(regions))
    }

    /** Drops everything currently tracked, e.g. when the session ends. */
    suspend fun clear() {
        lock.withLock { previousIds = emptySet() }
        events.emit(TextSourceEvent.Cleared)
    }

    private fun toElements(regions: List<TextRegion>): List<TextElement> {
        val occurrences = mutableMapOf<String, Int>()
        generation += 1

        return regions.map { region ->
            val index = occurrences.getOrDefault(region.text, 0)
            occurrences[region.text] = index + 1

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
                bounds = region.bounds,
                sourceType = TextSourceType.OCR,
                source = SourceIdentity(),
                revision = Revision(generation),
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
