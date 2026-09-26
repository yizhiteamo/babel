package com.babel.domain.translation

import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRuntimeState
import com.babel.core.testing.FakeSettingsRepository
import com.babel.domain.acquisition.TextSourceEvent
import com.babel.domain.render.RenderUpdate
import com.babel.domain.settings.BabelSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Coming back up the way the user left it.
 *
 * The bug this fixes was silent in both directions: pause lived only in the
 * coordinator's memory, the service started it on every connect, and nothing
 * recorded that anybody had ever paused. A phone that killed the process
 * therefore switched translation back on for someone who had switched it off,
 * and left no trace to notice.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TranslationStartupTest {

    /**
     * Reaches `Running` the way the real one does: **asynchronously**.
     *
     * The first version of this fake went straight to `Running` inside
     * `start()`, and the test passed against code that did not work at all on
     * the device — `pause()` only acts from `Running`, and the real
     * coordinator is still `Starting` when `start()` returns. A fake kinder
     * than the contract is worse than no fake.
     *
     * `StandardTestDispatcher` and not `Unconfined`, for the same reason: the
     * unconfined one runs the launch before `start()` returns, which is the
     * kindness all over again.
     */
    private class FakeCoordinator(private val scope: CoroutineScope) : TranslationCoordinator {
        private val _runtimeState =
            MutableStateFlow<TranslationRuntimeState>(TranslationRuntimeState.Disabled)
        override val runtimeState: StateFlow<TranslationRuntimeState> = _runtimeState
        override val providerFailure: StateFlow<TranslationError?> = MutableStateFlow(null)
        override val renderUpdates: Flow<RenderUpdate> = emptyFlow()

        val calls = mutableListOf<String>()

        override fun start() {
            calls += "start"
            if (_runtimeState.value == TranslationRuntimeState.Paused) {
                _runtimeState.value = TranslationRuntimeState.Running
                return
            }
            _runtimeState.value = TranslationRuntimeState.Starting
            scope.launch { _runtimeState.value = TranslationRuntimeState.Running }
        }

        override fun pause() {
            calls += "pause"
            if (_runtimeState.value == TranslationRuntimeState.Running) {
                _runtimeState.value = TranslationRuntimeState.Paused
            }
        }

        override fun stop() {
            calls += "stop"
            _runtimeState.value = TranslationRuntimeState.Disabled
        }

        override fun submit(event: TextSourceEvent) = Unit
    }

    @Test
    fun `an ordinary start just runs`() = runTest {
        val coordinator = FakeCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val settings = FakeSettingsRepository()

        TranslationStartup.restore(coordinator, settings)

        assertEquals(TranslationRuntimeState.Running, coordinator.runtimeState.value)
        assertEquals(listOf("start"), coordinator.calls)
    }

    @Test
    fun `somebody who paused stays paused`() = runTest {
        val coordinator = FakeCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val settings = FakeSettingsRepository(BabelSettings(translationPaused = true))

        TranslationStartup.restore(coordinator, settings)

        assertEquals(TranslationRuntimeState.Paused, coordinator.runtimeState.value)
    }

    /**
     * Not merely "do not start". The coordinator can only reach `Paused` from
     * `Running`, and `Disabled` is a different state that the home screen reads
     * — and labels — differently.
     */
    @Test
    fun `restoring a pause goes through running, not around it`() = runTest {
        val coordinator = FakeCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler)))
        val settings = FakeSettingsRepository(BabelSettings(translationPaused = true))

        TranslationStartup.restore(coordinator, settings)

        assertEquals(listOf("start", "pause"), coordinator.calls)
    }

    /**
     * The asymmetry that makes the whole thing work: only the user's own
     * actions write the setting. If a restore wrote, it would erase the very
     * decision it is restoring — which is how the original bug felt from the
     * outside.
     */
    @Test
    fun `restoring is not itself a decision, so it writes nothing`() = runTest {
        for (paused in listOf(true, false)) {
            val settings = FakeSettingsRepository(BabelSettings(translationPaused = paused))

            TranslationStartup.restore(
                FakeCoordinator(CoroutineScope(StandardTestDispatcher(testScheduler))),
                settings,
            )

            assertEquals(0, settings.pauseWrites, "a restore wrote the setting (paused=$paused)")
            assertEquals(paused, settings.current.translationPaused)
        }
    }
}
