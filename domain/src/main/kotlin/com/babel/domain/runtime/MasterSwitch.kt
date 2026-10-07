package com.babel.domain.runtime

import com.babel.core.model.TranslationRuntimeState
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.settings.SwitchedOff
import com.babel.domain.translation.TranslationCoordinator
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ScreenCaptureController
import kotlinx.coroutines.flow.first

/**
 * One switch for all of translation, separate from the accessibility service.
 *
 * Measured on a HyperOS phone: every time its cleaner ran, it force-stopped
 * Babel, and a force stop takes an accessibility service out of the enabled
 * list — so the user went back to system settings by hand each time
 * (`docs/systems/capabilities.md`). Clearing the app was also how they turned
 * translation off when they did not need it. This gives that second job its
 * own control, so the service can stay on.
 *
 * Built only from what the two cards on the home screen already do — pause,
 * resume, start and stop — so the switch cannot reach a state the cards
 * cannot, and the cards keep telling the truth about it.
 *
 * Here rather than in the tile because an Android `TileService` cannot be
 * built in a JVM test, and this is the part that can go wrong quietly.
 */
object MasterSwitch {

    /** On while either path is working. */
    fun isOn(text: TranslationRuntimeState, manga: CaptureState): Boolean =
        text == TranslationRuntimeState.Running || manga == CaptureState.ACTIVE

    /**
     * Takes down whatever is running and writes down what that was.
     *
     * Through the same calls as the home screen's buttons, so pausing here is
     * the user's decision exactly as pausing there is, and is remembered the
     * same way.
     */
    suspend fun turnOff(
        coordinator: TranslationCoordinator,
        capture: ScreenCaptureController,
        settings: SettingsRepository,
    ) {
        val held = SwitchedOff(
            text = coordinator.runtimeState.value == TranslationRuntimeState.Running,
            manga = capture.state.value == CaptureState.ACTIVE,
        )
        if (!held.text && !held.manga) return

        settings.setSwitchedOff(held)
        if (held.text) {
            coordinator.pause()
            settings.setTranslationPaused(true)
        }
        if (held.manga) capture.stop()
    }

    /**
     * Puts back what [turnOff] took down; text alone when nothing is recorded.
     *
     * Text is the default because it is what the app does first and costs
     * nothing until a screen is read. Manga mode takes pictures of the screen,
     * so it comes back only if it was on when the switch went off.
     */
    suspend fun turnOn(
        coordinator: TranslationCoordinator,
        capture: ScreenCaptureController,
        settings: SettingsRepository,
    ) {
        val held = settings.settings.first().switchedOff ?: SwitchedOff(text = true, manga = false)
        if (held.text && coordinator.runtimeState.value == TranslationRuntimeState.Paused) {
            coordinator.start()
            settings.setTranslationPaused(false)
        }
        if (held.manga) capture.start()
        settings.setSwitchedOff(null)
    }
}
