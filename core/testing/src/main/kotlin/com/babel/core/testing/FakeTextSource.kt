package com.babel.core.testing

import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.acquisition.TextSource
import com.babel.domain.acquisition.TextSourceEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Drives the pipeline without an AccessibilityService. Mirrors what a real
 * adapter emits: upserts with rising revisions, explicit removals, and a clear
 * on window change.
 */
class FakeTextSource(
    override val sourceType: TextSourceType = TextSourceType.ACCESSIBILITY,
) : TextSource {

    private val events = MutableSharedFlow<TextSourceEvent>(extraBufferCapacity = 64)

    override fun events(): Flow<TextSourceEvent> = events

    suspend fun emit(event: TextSourceEvent) {
        events.emit(event)
    }

    suspend fun upsert(vararg elements: TextElement) {
        emit(TextSourceEvent.Upserted(elements.toList()))
    }

    suspend fun remove(vararg ids: String) {
        emit(TextSourceEvent.Removed(ids.map(::TextElementId)))
    }

    suspend fun clear() {
        emit(TextSourceEvent.Cleared(sourceType))
    }
}
