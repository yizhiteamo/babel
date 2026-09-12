package com.babel.domain.translation

import com.babel.core.model.TranslationRuntimeState
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.render.RenderUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The pipeline's single decision point: resolve languages, consult the cache,
 * invoke a provider, reject stale work, publish render updates, and own
 * authoritative runtime state.
 *
 * Everything upstream (acquisition) and downstream (rendering) stays unaware of
 * providers and of each other.
 */
interface TranslationCoordinator {
    val runtimeState: StateFlow<TranslationRuntimeState>

    val renderUpdates: Flow<RenderUpdate>

    fun start()

    fun pause()

    /** Stops work and clears visible translations. */
    fun stop()

    /**
     * Feeds normalized acquisition output in. Duplicate events are coalesced and
     * results for superseded revisions are discarded rather than rendered.
     */
    fun submit(event: TextSourceEvent)
}
