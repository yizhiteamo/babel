package com.babel.app.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.babel.app.MainActivity
import com.babel.app.R
import com.babel.core.model.TranslationRuntimeState
import com.babel.domain.runtime.AccessibilityRecovery
import com.babel.domain.runtime.MasterSwitch
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.translation.TranslationCoordinator
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ScreenCaptureController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Translation on and off from quick settings, with the accessibility service
 * left alone.
 *
 * Clearing the app was how a user turned translation off, and on a HyperOS
 * phone clearing force-stops it, which takes the service out of the enabled
 * list — so turning translation back on meant a trip through system settings
 * (`docs/systems/capabilities.md`). The switch itself is [MasterSwitch]; this
 * only shows it and passes taps on.
 *
 * In `:app` because it is an entry point, like the home screen: it reads
 * state and calls the same pipeline operations, and holds no acquisition or
 * capture logic of its own.
 */
@AndroidEntryPoint
class BabelTileService : TileService() {

    @Inject
    lateinit var coordinator: TranslationCoordinator

    @Inject
    lateinit var capture: ScreenCaptureController

    @Inject
    lateinit var settings: SettingsRepository

    @Inject
    lateinit var recovery: AccessibilityRecovery

    /** Taps outlive the panel closing, so they get the service's lifetime. */
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** While the panel is showing the tile. */
    private var listening: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listening = scope
        scope.launch {
            combine(coordinator.runtimeState, capture.state) { text, manga -> text to manga }
                .collect { (text, manga) -> show(text, manga) }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // The coordinator is started by the service as it connects, so
        // Disabled means there is no service: nothing to switch.
        if (coordinator.runtimeState.value == TranslationRuntimeState.Disabled) {
            recovery.restoreIfDropped()
            openApp()
            return
        }
        work.launch {
            if (MasterSwitch.isOn(coordinator.runtimeState.value, capture.state.value)) {
                MasterSwitch.turnOff(coordinator, capture, settings)
            } else {
                MasterSwitch.turnOn(coordinator, capture, settings)
            }
        }
    }

    override fun onDestroy() {
        work.cancel()
        super.onDestroy()
    }

    private fun show(text: TranslationRuntimeState, manga: CaptureState) {
        val tile = qsTile ?: return
        val serviceDown = text == TranslationRuntimeState.Disabled
        val on = MasterSwitch.isOn(text, manga)
        tile.label = getString(R.string.tile_label)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    serviceDown -> R.string.tile_unavailable
                    on -> R.string.tile_on
                    else -> R.string.tile_off
                },
            )
        }
        tile.updateTile()
    }

    /** The home screen, which says what is missing and leads to it. */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
