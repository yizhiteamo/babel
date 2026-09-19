package com.babel.app.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.babel.core.model.Capability
import com.babel.core.model.CapabilityState
import com.babel.core.model.CapabilityStatus
import com.babel.core.model.TranslationRuntimeState
import com.babel.domain.vision.CaptureState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The difference between translating and being seen to translate.
 *
 * Without the overlay permission the pipeline reads the screen and translates
 * as usual, and `OverlayRenderer` throws the result away — it logs "nothing can
 * be drawn". The cards used to report the coordinator's own state, so one said
 * "running" on a screen where nothing appeared, while the line above it said
 * two permissions were still needed.
 *
 * On the device because [CapabilityState] and the enums come from modules built
 * for Android; the assertions themselves are about plain data.
 */
@RunWith(AndroidJUnit4::class)
class HomeUiStateTest {

    private fun state(
        overlay: Boolean,
        runtime: TranslationRuntimeState = TranslationRuntimeState.Running,
        capture: CaptureState = CaptureState.ACTIVE,
    ) = HomeUiState(
        capabilities = CapabilityState(
            mapOf(
                Capability.ACCESSIBILITY_SERVICE to CapabilityStatus.AVAILABLE,
                Capability.OVERLAY_WINDOW to
                    if (overlay) CapabilityStatus.AVAILABLE else CapabilityStatus.NOT_GRANTED,
            ),
        ),
        runtimeState = runtime,
        captureState = capture,
    )

    @Test
    fun withBothPermissionsTheWorkIsVisible() {
        val ready = state(overlay = true)

        assertTrue(ready.translationsCanBeSeen)
        assertFalse(ready.translatingButUnseen)
        assertFalse(ready.capturingButUnseen)
    }

    /** The state this was written for, and the one the device was in. */
    @Test
    fun withoutTheOverlayPermissionTheWorkIsInvisible() {
        val blind = state(overlay = false)

        assertFalse(blind.translationsCanBeSeen)
        assertTrue("text translation runs but cannot be seen", blind.translatingButUnseen)
        assertTrue("manga mode runs but cannot be seen", blind.capturingButUnseen)
    }

    /**
     * Nothing running is not the same as running invisibly, and the card should
     * keep saying "off" rather than warning about a permission that is not
     * stopping anything yet.
     */
    @Test
    fun aStoppedPipelineIsNotReportedAsInvisible() {
        val stopped = state(
            overlay = false,
            runtime = TranslationRuntimeState.Disabled,
            capture = CaptureState.IDLE,
        )

        assertFalse(stopped.translatingButUnseen)
        assertFalse(stopped.capturingButUnseen)
    }

    /** Paused counts as running for this: it is still not going to be seen. */
    @Test
    fun aPausedPipelineWithoutTheOverlayIsStillUnseen() {
        val paused = state(overlay = false, runtime = TranslationRuntimeState.Paused)

        assertTrue(paused.translatingButUnseen)
    }
}
