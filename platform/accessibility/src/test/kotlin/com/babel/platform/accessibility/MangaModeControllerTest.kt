package com.babel.platform.accessibility

import android.graphics.Bitmap
import com.babel.core.testing.FakeSettingsRepository
import com.babel.core.testing.RecordingLogger
import com.babel.domain.settings.BabelSettings
import com.babel.domain.vision.CaptureState
import com.babel.platform.screen.ScreenFrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class MangaModeControllerTest {

    private class FakeFrames(
        override var isAvailable: Boolean,
        override var isSupported: Boolean = true,
    ) : ScreenFrameSource {
        override suspend fun latestFrame(): Bitmap? = null
    }

    /**
     * Unconfined, so a write or a restore has happened by the time the
     * assertion looks. Both are one-shot launches rather than collectors, so
     * there is nothing here that needs the standard dispatcher's ordering.
     */
    private fun TestScope.controller(
        available: Boolean = true,
        supported: Boolean = true,
        stored: Boolean = false,
    ): Triple<MangaModeController, FakeFrames, FakeSettingsRepository> {
        val frames = FakeFrames(available, supported)
        val settings = FakeSettingsRepository(BabelSettings(mangaMode = stored))
        val mode = MangaModeController(
            frames = frames,
            settings = settings,
            scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            logger = RecordingLogger(),
        )
        return Triple(mode, frames, settings)
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

    // ---- what the user chose, and what merely happened ---------------------

    @Test
    fun `switching it on is remembered`() = runTest {
        val (mode, _, settings) = controller()

        mode.start()

        assertTrue(settings.current.mangaMode, "the choice should outlive the process")
    }

    @Test
    fun `switching it off is remembered too`() = runTest {
        val (mode, _, settings) = controller(stored = true)

        mode.start()
        mode.stop()

        assertEquals(false, settings.current.mangaMode)
    }

    /**
     * Otherwise the mode would come on by itself later, on a device where the
     * user was told it could not run.
     */
    @Test
    fun `a start that could not happen is not remembered`() = runTest {
        val (mode, _, settings) = controller(available = false)

        mode.start()

        assertEquals(CaptureState.UNAVAILABLE, mode.state.value)
        assertEquals(0, settings.mangaModeWrites, "nothing happened, so nothing to record")
    }

    /**
     * The whole point of the separation. A mode taken down because the service
     * went away is not a decision to turn it off, and writing it as one would
     * mean the user had never turned it on.
     */
    @Test
    fun `losing the service is not a decision to switch it off`() = runTest {
        val (mode, frames, settings) = controller()
        mode.start()
        assertEquals(1, settings.mangaModeWrites)

        frames.isAvailable = false
        mode.onServiceAvailabilityChanged()

        assertEquals(CaptureState.UNAVAILABLE, mode.state.value)
        assertTrue(settings.current.mangaMode, "the choice stands; only the capability went")
        assertEquals(1, settings.mangaModeWrites, "and nothing was written")
    }

    /**
     * The case the user reported: the phone kills the process, the service goes
     * with it, and everything they had set up is gone. Coming back should put
     * it the way they left it.
     */
    @Test
    fun `the service coming back restores what the user left on`() = runTest {
        val (mode, _, settings) = controller(stored = true)

        mode.onServiceAvailabilityChanged()

        assertEquals(CaptureState.ACTIVE, mode.state.value)
        assertEquals(0, settings.mangaModeWrites, "restoring is not a new choice")
    }

    @Test
    fun `the service coming back leaves it off if that is how it was left`() = runTest {
        val (mode, _, settings) = controller(stored = false)

        mode.onServiceAvailabilityChanged()

        assertEquals(CaptureState.IDLE, mode.state.value)
        assertEquals(0, settings.mangaModeWrites)
    }
}
