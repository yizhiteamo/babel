package com.babel.domain.translation

import com.babel.core.model.TranslationRuntimeState
import com.babel.domain.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Brings translation back up the way the user left it.
 *
 * Pause lives in the coordinator's memory, and the accessibility service starts
 * the coordinator on every connect. That used to be unconditional, so a process
 * the phone killed brought translation back on for somebody who had
 * deliberately switched it off — and nothing anywhere recorded that they ever
 * had, so there was not even a trace to notice.
 *
 * Here rather than beside the service for the reason the rule needs testing at
 * all: an `AccessibilityService` cannot be built in a JVM test, and this is
 * exactly the kind of judgement that goes wrong quietly.
 */
object TranslationStartup {

    /**
     * Starts, then pauses again if that is where it was left.
     *
     * `start()` first, rather than simply not starting: the coordinator can
     * only reach `Paused` from `Running`, and leaving it `Disabled` is a
     * different state that the home screen reads — and labels — differently.
     *
     * And **waiting** for `Running`, because `start()` does not get there
     * before it returns — it goes through `Starting` and finishes on a
     * coroutine of its own. Pausing straight afterwards silently did nothing,
     * which is how the first version of this shipped a fix that changed
     * nothing on the device. Returns once translation is actually in the state
     * the user left it in.
     *
     * **Writes nothing.** This is a restore, and recording it as a decision
     * would erase the decision being restored. That asymmetry is the whole
     * point: only the user's own actions write the setting.
     */
    suspend fun restore(coordinator: TranslationCoordinator, settings: SettingsRepository) {
        coordinator.start()
        coordinator.runtimeState.first { it == TranslationRuntimeState.Running }
        if (settings.settings.first().translationPaused) coordinator.pause()
    }
}
