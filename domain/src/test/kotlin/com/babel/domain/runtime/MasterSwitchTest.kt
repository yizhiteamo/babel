package com.babel.domain.runtime

import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRuntimeState
import com.babel.core.testing.FakeSettingsRepository
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.render.RenderUpdate
import com.babel.domain.settings.BabelSettings
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.settings.SwitchedOff
import com.babel.domain.translation.TranslationCoordinator
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ScreenCaptureController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * The quick-settings switch: off takes down what is running, on puts back
 * exactly that.
 */
class MasterSwitchTest {

    private class FakeCoordinator(initial: TranslationRuntimeState) : TranslationCoordinator {
        private val _runtimeState = MutableStateFlow(initial)
        override val runtimeState: StateFlow<TranslationRuntimeState> = _runtimeState
        override val providerFailure: StateFlow<TranslationError?> = MutableStateFlow(null)
        override val renderUpdates: Flow<RenderUpdate> = emptyFlow()

        override fun start() {
            if (_runtimeState.value == TranslationRuntimeState.Paused) {
                _runtimeState.value = TranslationRuntimeState.Running
            }
        }

        override fun pause() {
            if (_runtimeState.value == TranslationRuntimeState.Running) {
                _runtimeState.value = TranslationRuntimeState.Paused
            }
        }

        override fun stop() {
            _runtimeState.value = TranslationRuntimeState.Disabled
        }

        override fun submit(event: TextSourceEvent) = Unit
    }

    /** Writes the setting on start and stop, as `MangaModeController` does. */
    private class FakeCapture(initial: CaptureState, private val settings: SettingsRepository) :
        ScreenCaptureController {
        private val _state = MutableStateFlow(initial)
        override val state: StateFlow<CaptureState> = _state

        override fun start() {
            _state.value = CaptureState.ACTIVE
            runBlocking { settings.setMangaMode(true) }
        }

        override fun stop() {
            _state.value = CaptureState.IDLE
            runBlocking { settings.setMangaMode(false) }
        }
    }

    @Test
    fun `off takes down both and remembers both`() = runTest {
        val settings = FakeSettingsRepository(BabelSettings(mangaMode = true))
        val text = FakeCoordinator(TranslationRuntimeState.Running)
        val manga = FakeCapture(CaptureState.ACTIVE, settings)

        MasterSwitch.turnOff(text, manga, settings)

        assertEquals(TranslationRuntimeState.Paused, text.runtimeState.value)
        assertEquals(CaptureState.IDLE, manga.state.value)
        assertEquals(SwitchedOff(text = true, manga = true), settings.current.switchedOff)
        assertTrue(settings.current.translationPaused)
        assertFalse(MasterSwitch.isOn(text.runtimeState.value, manga.state.value))
    }

    @Test
    fun `on puts back exactly what off took down`() = runTest {
        val settings = FakeSettingsRepository(BabelSettings(mangaMode = true))
        val text = FakeCoordinator(TranslationRuntimeState.Running)
        val manga = FakeCapture(CaptureState.ACTIVE, settings)

        MasterSwitch.turnOff(text, manga, settings)
        MasterSwitch.turnOn(text, manga, settings)

        assertEquals(TranslationRuntimeState.Running, text.runtimeState.value)
        assertEquals(CaptureState.ACTIVE, manga.state.value)
        assertFalse(settings.current.translationPaused)
        assertTrue(settings.current.mangaMode)
        assertNull(settings.current.switchedOff)
    }

    /** Text the user had paused themselves stays paused through an off and on. */
    @Test
    fun `a path that was already off stays off`() = runTest {
        val settings = FakeSettingsRepository(BabelSettings(translationPaused = true, mangaMode = true))
        val text = FakeCoordinator(TranslationRuntimeState.Paused)
        val manga = FakeCapture(CaptureState.ACTIVE, settings)

        MasterSwitch.turnOff(text, manga, settings)
        MasterSwitch.turnOn(text, manga, settings)

        assertEquals(TranslationRuntimeState.Paused, text.runtimeState.value)
        assertTrue(settings.current.translationPaused)
        assertEquals(CaptureState.ACTIVE, manga.state.value)
    }

    /** Off then on across a process death: what was recorded is all there is. */
    @Test
    fun `what off recorded survives into a fresh process`() = runTest {
        val settings = FakeSettingsRepository(
            BabelSettings(
                translationPaused = true,
                switchedOff = SwitchedOff(text = true, manga = true),
            ),
        )
        val text = FakeCoordinator(TranslationRuntimeState.Paused)
        val manga = FakeCapture(CaptureState.IDLE, settings)

        MasterSwitch.turnOn(text, manga, settings)

        assertEquals(TranslationRuntimeState.Running, text.runtimeState.value)
        assertEquals(CaptureState.ACTIVE, manga.state.value)
    }

    /** Manga mode takes pictures of the screen; it never comes on unasked. */
    @Test
    fun `on with nothing recorded brings back text only`() = runTest {
        val settings = FakeSettingsRepository(BabelSettings(translationPaused = true))
        val text = FakeCoordinator(TranslationRuntimeState.Paused)
        val manga = FakeCapture(CaptureState.IDLE, settings)

        MasterSwitch.turnOn(text, manga, settings)

        assertEquals(TranslationRuntimeState.Running, text.runtimeState.value)
        assertEquals(CaptureState.IDLE, manga.state.value)
        assertEquals(0, settings.mangaModeWrites)
    }

    @Test
    fun `off with nothing running records nothing`() = runTest {
        val settings = FakeSettingsRepository()
        val text = FakeCoordinator(TranslationRuntimeState.Paused)
        val manga = FakeCapture(CaptureState.IDLE, settings)

        MasterSwitch.turnOff(text, manga, settings)

        assertNull(settings.current.switchedOff)
        assertEquals(0, settings.pauseWrites)
    }
}
