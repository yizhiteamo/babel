package com.babel.domain.acquisition

import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import kotlinx.coroutines.flow.Flow

/**
 * What an acquisition adapter reports. Removal is explicit so the renderer can
 * drop overlays the moment their source text leaves the screen — scrolling must
 * not leave stale translations behind.
 */
sealed interface TextSourceEvent {
    /** New or changed elements. Each carries a fresh revision. */
    data class Upserted(val elements: List<TextElement>) : TextSourceEvent

    data class Removed(val ids: List<TextElementId>) : TextSourceEvent

    /** Window/app changed, or the source shut down; drop everything. */
    data object Cleared : TextSourceEvent
}

/**
 * A normalized text producer. Implementations live in `:platform:*` and convert
 * platform objects into [TextElement] before emitting.
 *
 * Implementations must not call translation providers (ADR 005).
 */
interface TextSource {
    val sourceType: TextSourceType

    fun events(): Flow<TextSourceEvent>
}
