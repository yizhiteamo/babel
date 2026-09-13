package com.babel.platform.accessibility

import android.graphics.Bitmap
import com.babel.core.testing.RecordingLogger
import com.babel.domain.vision.CaptureState
import com.babel.platform.screen.ScreenFrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class MangaModeControllerTest {

    private class FakeFrames(override var isAvailable: Boolean) : ScreenFrameSource {
        override suspend fun latestFrame(): Bitmap? = null
    }

    private fun controller(available: Boolean = true): Pair<MangaModeController, FakeFrames> {
        val frames = FakeFrames(available)
        return MangaModeController(frames, RecordingLogger()) to frames
    }

    @Test
    fun `starts when frames can be taken`() = runTest {
        val (mode, _) = controller()

        mode.start()

        assertEquals(CaptureState.ACTIVE, mode.state.value)
    }

    /**
     * Below Android 11, or with the accessibility service switched off, there is
     * nothing to take pictures with. Saying so beats failing silently.
     */
    @Test
    fun `reports unavailable rather than pretending to start`() = runTest {
        val (mode, _) = controller(available = false)

        mode.start()

        assertEquals(CaptureState.UNAVAILABLE, mode.state.value)
    }

    /**
     * The user can switch the accessibility service off from system settings
     * while the mode is running. Leaving it ACTIVE would offer to stop
     * something that is no longer happening.
     */
    @Test
    fun `losing the service takes the mode down with it`() = runTest {
        val (mode, frames) = controller()
        mode.start()

        frames.isAvailable = false
        mode.onServiceAvailabilityChanged()

        assertEquals(CaptureState.UNAVAILABLE, mode.state.value)
    }

    @Test
    fun `regaining the service makes the mode offerable again`() = runTest {
        val (mode, frames) = controller(available = false)
        mode.start()

        frames.isAvailable = true
        mode.onServiceAvailabilityChanged()

        assertEquals(CaptureState.IDLE, mode.state.value)
    }

    @Test
    fun `stopping is idempotent`() = runTest {
        val (mode, _) = controller()

        mode.stop()
        mode.stop()

        assertEquals(CaptureState.IDLE, mode.state.value)
    }
}
