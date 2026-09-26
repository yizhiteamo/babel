package com.babel.domain.acquisition

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for a burst of change events to finish before anything acts on it.
 *
 * A scroll is not one event. A fling keeps firing for a second or more, and a
 * scan started from the **first** of them is reading a screen that is still
 * moving — worse, on the image path every one of those events clears the page,
 * so the scan in flight is thrown away when it tries to publish. Measured on
 * the emulator: four drags, **four scans abandoned part way**, and the
 * translations the user saw came from the scan after them.
 *
 * Waiting *longer* for the screen to settle was the first attempt at this and
 * it bought nothing — a doomed scan simply died later
 * (`CaptureTextSource.SETTLE_ATTEMPTS`). The thing to change is when the wait
 * starts, not how long it runs.
 *
 * Lives here rather than beside the service because this is the judgement, and
 * judgement should be testable: an `AccessibilityService` cannot be built in a
 * JVM test, and this can be driven entirely on `runTest`'s virtual clock.
 */
object ScanDebounce {

    /**
     * Returns once [requests] has been quiet for [quietMs].
     *
     * @param limitMs the whole budget. A page that never stops changing — a
     *   video, an animation — would otherwise keep the caller here for ever,
     *   and the 1.5s tick that covers that case cannot run while this does not
     *   return.
     *
     * Both are coroutine timeouts rather than readings of a clock, which is
     * what lets `runTest` drive this on virtual time:
     * `System.currentTimeMillis()` does not follow it, and a rule written
     * against it would quietly measure nothing.
     *
     * Returns as soon as the channel closes, so a cancelled scope does not wait
     * out the timeout.
     */
    suspend fun awaitQuiet(
        requests: ReceiveChannel<Unit>,
        quietMs: Long,
        limitMs: Long,
    ) {
        withTimeoutOrNull(limitMs) {
            while (true) {
                val another = withTimeoutOrNull(quietMs) {
                    requests.receiveCatching().getOrNull()
                }
                // Either nothing arrived in time, or the channel is done. Both
                // mean there is nothing more coming worth waiting for.
                if (another == null) return@withTimeoutOrNull
            }
        }
    }
}
