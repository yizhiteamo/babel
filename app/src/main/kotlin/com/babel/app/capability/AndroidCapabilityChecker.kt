package com.babel.app.capability

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import com.babel.core.model.Capability
import com.babel.core.model.CapabilityState
import com.babel.core.model.CapabilityStatus
import com.babel.domain.runtime.CapabilityChecker
import com.babel.platform.accessibility.BabelAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Answers whether the platform will let the translator run at all — separate
 * from runtime state, which says what it is currently doing
 * (`docs/systems/capabilities.md`).
 *
 * Both permissions are granted in system settings, outside this app. The two
 * accessibility keys are watched, so a grant that goes away while the app is
 * open is noticed; the overlay permission is an app-op with no such key and
 * still rides on [refresh] when the user returns.
 */
@Singleton
class AndroidCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : CapabilityChecker {

    private val _state = MutableStateFlow(CapabilityState())
    override val state: StateFlow<CapabilityState> = _state.asStateFlow()

    /** Held so it is not collected while still registered. */
    private val accessibilityObserver =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }

    init {
        refresh()
        // Watched rather than polled on resume, because the grant can go away
        // with the app in the foreground and nothing to prompt a re-read: the
        // user switches the service off from the notification shade, or — on a
        // HyperOS device, measured — the vendor security centre strips it from
        // the list some seconds after the user grants it
        // (`docs/systems/capabilities.md`). Resume-only reporting was green for
        // twenty-five seconds after the service had already been torn down.
        //
        // Never unregistered: this is a @Singleton on the application context,
        // so it is wanted for as long as the process lives.
        for (key in OBSERVED_KEYS) {
            context.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(key),
                false,
                accessibilityObserver,
            )
        }
    }

    override fun refresh() {
        _state.value = CapabilityState(
            mapOf(
                Capability.ACCESSIBILITY_SERVICE to accessibilityStatus(),
                Capability.OVERLAY_WINDOW to overlayStatus(),
            ),
        )
    }

    override fun required(): Set<Capability> = setOf(
        Capability.ACCESSIBILITY_SERVICE,
        Capability.OVERLAY_WINDOW,
    )

    /**
     * Read from the settings rather than from whether our own service object is
     * alive: the user can disable it outside the app at any moment, and the
     * setting is the authority.
     *
     * **Two settings, not one.** The services list says who the user has
     * permitted; `ACCESSIBILITY_ENABLED` says whether accessibility is running
     * at all. They come apart — a device that failed to bind the service at
     * boot keeps the name in the list with the master switch at zero, and this
     * once reported the service as granted while nothing was bound. The app
     * then contradicted itself on one screen: a permission card saying yes, and
     * manga mode saying it could not start.
     */
    private fun accessibilityStatus(): CapabilityStatus =
        if (
            isAccessibilityServiceRunning(
                masterSwitch = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0,
                ),
                enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ),
                expected = ComponentName(
                    context,
                    BabelAccessibilityService::class.java,
                ).flattenToString(),
            )
        ) {
            CapabilityStatus.AVAILABLE
        } else {
            CapabilityStatus.NOT_GRANTED
        }

    private fun overlayStatus(): CapabilityStatus =
        if (Settings.canDrawOverlays(context)) {
            CapabilityStatus.AVAILABLE
        } else {
            CapabilityStatus.NOT_GRANTED
        }

    internal companion object {
        private const val SERVICE_SEPARATOR = ':'

        /** Both, for the same reason [accessibilityStatus] reads both. */
        private val OBSERVED_KEYS = listOf(
            Settings.Secure.ACCESSIBILITY_ENABLED,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )

        /**
         * The decision on its own, with no `Context` in it, because this is the
         * part that was wrong and the part worth testing.
         *
         * Not airtight, and deliberately not pretending to be: if another
         * accessibility service is running, the master switch is one even when
         * Babel's own binding failed, and this would say yes. Rare, and the
         * side that actually needs a live service catches it — manga mode asks
         * whether the service object attached, not whether a setting says so.
         */
        fun isAccessibilityServiceRunning(
            masterSwitch: Int,
            enabledServices: String?,
            expected: String,
        ): Boolean {
            if (masterSwitch != 1) return false
            if (enabledServices.isNullOrEmpty()) return false

            val wanted = ComponentName.unflattenFromString(expected) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(SERVICE_SEPARATOR)
            splitter.setString(enabledServices)
            for (entry in splitter) {
                // Compared as components rather than as strings: the same
                // service can be written with a short class name or a long one.
                if (ComponentName.unflattenFromString(entry) == wanted) return true
            }
            return false
        }
    }
}
