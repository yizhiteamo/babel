package com.babel.app.capability

import android.content.ComponentName
import android.content.Context
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
 * Both permissions are granted in system settings, outside this app, so
 * nothing tells us when they change. [refresh] is therefore called when the
 * user returns to the app.
 */
@Singleton
class AndroidCapabilityChecker @Inject constructor(
    @ApplicationContext private val context: Context,
) : CapabilityChecker {

    private val _state = MutableStateFlow(CapabilityState())
    override val state: StateFlow<CapabilityState> = _state.asStateFlow()

    init {
        refresh()
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
     * Read from the enabled-services list rather than tracking whether our own
     * service object is alive: the user can disable it in system settings at
     * any moment, and the setting is the authority.
     */
    private fun accessibilityStatus(): CapabilityStatus {
        val expected = ComponentName(context, BabelAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return CapabilityStatus.NOT_GRANTED

        val splitter = TextUtils.SimpleStringSplitter(SERVICE_SEPARATOR)
        splitter.setString(enabled)
        for (entry in splitter) {
            val component = ComponentName.unflattenFromString(entry) ?: continue
            if (component == expected) return CapabilityStatus.AVAILABLE
        }
        return CapabilityStatus.NOT_GRANTED
    }

    private fun overlayStatus(): CapabilityStatus =
        if (Settings.canDrawOverlays(context)) {
            CapabilityStatus.AVAILABLE
        } else {
            CapabilityStatus.NOT_GRANTED
        }

    private companion object {
        const val SERVICE_SEPARATOR = ':'
    }
}
