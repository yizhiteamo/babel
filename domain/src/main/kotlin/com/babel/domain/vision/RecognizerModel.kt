package com.babel.domain.vision

import kotlinx.coroutines.flow.StateFlow

/**
 * The comic recogniser's weights, which do not travel with the app.
 *
 * The balloon detector is bundled; this one is 117MB and is fetched only if
 * someone asks for it (ADR 011). That makes "is it here yet" a state the
 * interface has to show and act on, which is why it is a contract rather than
 * an implementation detail of the module that downloads it.
 *
 * Expressed in the domain so the UI never sees the downloader: what a screen
 * needs is a state, a size and a verb, none of which are Android types.
 */
interface RecognizerModel {

    val state: StateFlow<RecognizerModelState>

    /** What the whole thing costs, for saying so before anyone agrees to it. */
    val totalBytes: Long

    /**
     * Whether the connection is one the user pays for by the megabyte.
     *
     * Reported rather than enforced: an unlimited plan can be reported as
     * metered, and refusing on that alone would be wrong. The screen asks.
     */
    fun isMetered(): Boolean

    /**
     * Fetches whatever is missing; does nothing when everything is present.
     *
     * Cancelling is safe and keeps the partial download, so calling again
     * continues rather than restarts.
     */
    suspend fun install()
}

sealed interface RecognizerModelState {

    /** Not on the device. Manga mode still works, with weaker recognition. */
    data object Absent : RecognizerModelState

    data class Running(val bytes: Long, val total: Long) : RecognizerModelState

    data object Installed : RecognizerModelState

    /**
     * Carries a cause this project named, never a server's words: an error body
     * can echo anything, and none of it belongs on a screen or in a log.
     */
    data class Failed(val cause: Cause) : RecognizerModelState {
        enum class Cause {
            /** The connection failed or the host refused. Worth retrying. */
            NETWORK,

            /**
             * What arrived was not what was expected. Retrying will not help:
             * the hashes are pinned to the files this was measured against, so
             * a mismatch means the app needs updating, not the device.
             */
            CORRUPT,

            UNEXPECTED,
        }
    }
}
