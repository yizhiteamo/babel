package com.babel.app.capability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import com.babel.platform.accessibility.BabelAccessibilityService

/**
 * The accessibility list, asked to scroll to Babel and highlight it.
 *
 * Re-enabling after a force stop was a hunt through a page of other apps'
 * services, so the extras the settings app's own search uses are passed along;
 * stock settings honours them and a ROM may not.
 *
 * Babel's own details page would be one step shorter and is not reachable:
 * `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` is hidden API, and on a
 * HyperOS phone starting it ended in `Permission Denial` — the activity wants a
 * permission only the system holds (`docs/systems/capabilities.md`).
 */
fun Context.openAccessibilitySettings() {
    val component = ComponentName(this, BabelAccessibilityService::class.java).flattenToString()
    val highlighted = Bundle().apply { putString(SETTINGS_FRAGMENT_ARGS_KEY, component) }
    startActivity(
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(SETTINGS_FRAGMENT_ARGS_KEY, component)
            .putExtra(SETTINGS_SHOW_FRAGMENT_ARGS, highlighted)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** The settings app's own extras for scrolling to and highlighting an entry. */
private const val SETTINGS_FRAGMENT_ARGS_KEY = ":settings:fragment_args_key"
private const val SETTINGS_SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
