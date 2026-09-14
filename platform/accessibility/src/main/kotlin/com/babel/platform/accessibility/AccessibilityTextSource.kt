package com.babel.platform.accessibility

import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextSource
import com.babel.domain.acquisition.TextSourceEvent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Turns successive screen scans into the incremental events the pipeline
 * expects.
 *
 * A scan reports everything currently visible, but emitting all of it every
 * time would make the coordinator re-examine the whole screen on each scroll
 * tick. Diffing against the previous scan here means downstream only sees what
 * actually changed, and — critically — text that scrolled off produces an
 * explicit [TextSourceEvent.Removed] so its overlay is torn down.
 */
@Singleton
class AccessibilityTextSource @Inject constructor() : TextSource {

    override val sourceType: TextSourceType = TextSourceType.ACCESSIBILITY

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 64)

    private val lock = Mutex()
    private var previousIds: Set<TextElementId> = emptySet()

    override fun events(): Flow<TextSourceEvent> = events.asSharedFlow()

    /** Publishes one complete scan of the visible screen. */
    suspend fun publish(elements: List<TextElement>) {
        val (removed, upserted) = lock.withLock {
            val currentIds = elements.mapTo(LinkedHashSet()) { it.id }
            val gone = previousIds - currentIds
            previousIds = currentIds
            gone to elements
        }

        if (removed.isNotEmpty()) events.emit(TextSourceEvent.Removed(removed.toList()))
        if (upserted.isNotEmpty()) events.emit(TextSourceEvent.Upserted(upserted))
    }

    /** The window or app changed; nothing previously seen is still valid. */
    suspend fun clear() {
        lock.withLock { previousIds = emptySet() }
        events.emit(TextSourceEvent.Cleared(sourceType))
    }
}
