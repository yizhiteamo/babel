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

    /**
     * Window/app changed, or the source shut down; drop everything **this
     * source** produced.
     *
     * The source has to say which it is. Both acquisition paths feed one
     * coordinator, and manga mode hands the screen back and forth between them
     * (`docs/systems/accessibility.md`), so a clear that dropped everything
     * would take the other path's live overlays with it — measured on a
     * device as translations vanishing the moment the other path stood down.
     */
    data class Cleared(val sourceType: TextSourceType) : TextSourceEvent
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
