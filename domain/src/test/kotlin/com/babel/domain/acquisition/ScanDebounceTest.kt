package com.babel.domain.acquisition

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest

/**
 * When a scan should start relative to a burst of change events.
 *
 * The numbers here are the service's: 250ms of quiet, inside a 1.5s budget.
 * What they have to produce is a scan that starts **after** a fling rather than
 * during it, because a scan started during one is thrown away — measured as
 * four abandoned scans out of four drags.
 *
 * All on virtual time, which is why both limits are coroutine timeouts rather
 * than readings of a clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScanDebounceTest {

    private val quiet = 250L
    private val limit = 1_500L

    @Test
    fun `one event is followed by one quiet period`() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)

        val started = currentTime
        ScanDebounce.awaitQuiet(requests, quiet, limit)

        assertEquals(quiet, currentTime - started)
    }

    @Test
    fun `a burst is waited out rather than acted on at its first event`() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        // A fling: events every 100ms for a second, which is what the
        // accessibility service really receives.
        val fling = launch {
            repeat(10) {
                delay(100)
                requests.trySend(Unit)
            }
        }

        val started = currentTime
        ScanDebounce.awaitQuiet(requests, quiet, limit)
        val waited = currentTime - started
        fling.join()

        // The burst ends at 1000ms; the quiet period then has to elapse on top.
        assertEquals(1000L + quiet, waited, "should have waited out the whole burst")
    }

    @Test
    fun `a page that never stops does not hold the caller for ever`() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        val animation = launch {
            while (true) {
                delay(50)
                requests.trySend(Unit)
            }
        }

        val started = currentTime
        ScanDebounce.awaitQuiet(requests, quiet, limit)
        val waited = currentTime - started
        animation.cancel()

        // The budget and no more. A video would otherwise keep the event loop
        // here permanently; the 1.5s tick is what covers that case, and it
        // cannot help if this never returns.
        assertEquals(limit, waited)
    }

    @Test
    fun `a closed channel returns at once rather than timing out`() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        requests.close()

        val started = currentTime
        ScanDebounce.awaitQuiet(requests, quiet, limit)

        assertEquals(0L, currentTime - started, "a torn-down scope should not wait")
    }

    @Test
    fun `quiet is measured from the last event, not the first`() = runTest {
        val requests = Channel<Unit>(Channel.CONFLATED)
        // Two events 200ms apart — inside the quiet period, so the second must
        // restart it. The old leading debounce would have acted at 250ms, in
        // the middle of the movement.
        launch {
            delay(200)
            requests.trySend(Unit)
        }

        val started = currentTime
        ScanDebounce.awaitQuiet(requests, quiet, limit)

        assertTrue(
            currentTime - started >= 200 + quiet,
            "waited only ${currentTime - started}ms, so the second event did not count",
        )
    }
}
