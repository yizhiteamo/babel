package com.babel.platform.accessibility

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.babel.core.common.BabelLogger
import com.babel.domain.runtime.AccessibilityRecovery
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Re-enables [BabelAccessibilityService] after a force stop took it out of the
 * enabled list.
 *
 * ## Telling a force stop from the user switching it off
 *
 * Both end with the service gone from the list, and only one of them may be
 * undone. The difference is whether the service heard about it: switching it
 * off in system settings — or the vendor's security centre revoking it
 * (`docs/systems/capabilities.md`) — unbinds the service, and
 * [BabelAccessibilityService.onUnbind] runs. A force stop kills the process
 * first, and nothing runs at all.
 *
 * So the service writes "wanted" as it connects and "not wanted" as it is
 * unbound, and a "wanted" still standing while the service is not connected
 * can only mean the process died under it. A package update is the same case,
 * and restoring then is right too: the grant survives an update in the list
 * while the binding does not (`docs/systems/testing.md`).
 *
 * In shared preferences rather than the settings repository: it is not a user
 * setting, and the unbind write has to land before the process may go, which
 * `commit()` gives and an asynchronous store does not.
 */
@Singleton
class AccessibilityServiceRestorer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: BabelLogger,
) : AccessibilityRecovery {

    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    /** Whether the service is connected in this process right now. */
    @Volatile
    private var connected = false

    /** When this process last wrote the list, so a bind in progress is not interrupted. */
    @Volatile
    private var lastWriteAt = 0L

    private val component: String
        get() = ComponentName(context, BabelAccessibilityService::class.java).flattenToString()

    override val canRestore: Boolean
        get() = context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    internal fun onConnected() {
        connected = true
        preferences.edit().putBoolean(KEY_WANTED, true).commit()
    }

    internal fun onUnbound() {
        connected = false
        preferences.edit().putBoolean(KEY_WANTED, false).commit()
    }

    override fun restoreIfDropped(): Boolean {
        val now = System.currentTimeMillis()
        if (
            !shouldRestore(
                wanted = preferences.getBoolean(KEY_WANTED, false),
                granted = canRestore,
                connected = connected,
                sinceLastWriteMs = now - lastWriteAt,
            )
        ) {
            return false
        }

        val resolver = context.contentResolver
        val ours = ComponentName.unflattenFromString(component)
        val lists = EnabledList.rewrite(
            current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            ours = component,
            isOurs = { ComponentName.unflattenFromString(it) == ours },
        )

        return try {
            // Without, then with: writing the value already stored notifies
            // nobody, and after a force stop the list may still name the
            // service while nothing is bound (`docs/systems/testing.md`).
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, lists.without)
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, lists.with)
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            lastWriteAt = now
            // What was done, never the list: it names the user's other apps.
            logger.info(TAG, "accessibility service re-enabled after it was dropped without an unbind")
            true
        } catch (denied: SecurityException) {
            logger.warn(TAG, "not allowed to write the accessibility list", denied)
            false
        }
    }

    internal companion object {
        private const val TAG = "A11yRestore"
        private const val PREFERENCES = "babel.accessibility"
        private const val KEY_WANTED = "service_wanted"

        /** Binding takes up to about fifteen seconds on a phone (`docs/systems/testing.md`). */
        const val BIND_GRACE_MS = 30_000L

        fun shouldRestore(wanted: Boolean, granted: Boolean, connected: Boolean, sinceLastWriteMs: Long): Boolean =
            wanted && granted && !connected && sinceLastWriteMs >= BIND_GRACE_MS
    }
}

/** The enabled-services list as text, with no `Context` in it, so it can be tested. */
internal object EnabledList {

    class Rewrite(val without: String, val with: String)

    private const val SEPARATOR = ':'

    /**
     * The list without this service and with it at the end, every other entry
     * kept as written — the same service can appear in a short or a long form,
     * so [isOurs] decides what is this one.
     */
    fun rewrite(current: String?, ours: String, isOurs: (String) -> Boolean): Rewrite {
        val others = current.orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotBlank() && !isOurs(it) }
        return Rewrite(
            without = others.joinToString(SEPARATOR.toString()),
            with = (others + ours).joinToString(SEPARATOR.toString()),
        )
    }
}
