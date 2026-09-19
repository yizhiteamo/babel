package com.babel.platform.accessibility

import android.graphics.Bitmap
import com.babel.core.testing.RecordingLogger
import com.babel.domain.vision.CaptureState
import com.babel.platform.screen.ScreenFrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class MangaModeControllerTest {

    private class FakeFrames(
        override var isAvailable: Boolean,
        override var isSupported: Boolean = true,
    ) : ScreenFrameSource {
        override suspend fun latestFrame(): Bitmap? = null
    }

    private fun controller(
        available: Boolean = true,
        supported: Boolean = true,
    ): Pair<MangaModeController, FakeFrames> {
        val frames = FakeFrames(available, supported)
        return MangaModeController(frames, RecordingLogger()) to frames
    }

    @Test
    fun `starts when frames can be taken`() = runTest {
        val (mode, _) = controller()

        mode.start()

        assertEquals(CaptureState.ACTIVE, mode.state.value)
    }

    /**
     * With the accessibility service switched off there is nothing to take
     * pictures with. Saying so beats failing silently — and saying *this*
     * rather than the other one is what tells the user it is fixable.
     */
    @Test
    fun `reports unavailable rather than pretending to start`() = runTest {
        val (mode, _) = controller(available = false)

        mode.start()

        assertEquals(CaptureState.UNAVAILABLE, mode.state.value)
    }

    /**
     * A device below Android 11 gets a different answer, because no amount of
     * switching services on will help. One message covering both used to name
     * the version requirement first on devices that always satisfied it.
     */
    @Test
    fun `an old device is unsupported, not merely unavailable`() = runTest {
        val (mode, _) = controller(available = false, supported = false)

        mode.start()

        assertEquals(CaptureState.UNSUPPORTED, mode.state.value)
    }

    /** And it stays that way: a service connecting cannot make a device newer. */
    @Test
    fun `an unsupported device is not rescued by the service connecting`() = runTest {
        val (mode, frames) = controller(available = false, supported = false)
        mode.start()

        // What a connection would look like on a device that cannot use it.
        frames.isAvailable = false
        mode.onServiceAvailabilityChanged()

        assertEquals(CaptureState.UNSUPPORTED, mode.state.value)
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
