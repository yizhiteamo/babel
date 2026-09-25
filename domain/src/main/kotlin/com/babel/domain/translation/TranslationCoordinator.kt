package com.babel.domain.translation

import com.babel.core.model.TranslationError
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

    /**
     * A failure that looks like the configuration rather than the weather, or
     * null while the provider is answering.
     *
     * Separate from [runtimeState] on purpose. A rejected key does not change
     * what this is doing — it is still running, still tracking elements, still
     * rendering whatever the cache can answer — and folding the two together
     * would hide the pause button at the moment somebody wants it
     * (`TranslationRuntimeState`).
     *
     * Only latched for errors that will not fix themselves, and only after
     * enough of them in a row to rule out one awkward element. Cleared by the
     * next success. Without it a wrong key is invisible: the screen simply has
     * no translations on it, which looks the same as a page with no text.
     */
    val providerFailure: StateFlow<TranslationError?>

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
