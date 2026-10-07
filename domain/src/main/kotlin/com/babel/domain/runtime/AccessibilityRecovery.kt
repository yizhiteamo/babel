package com.babel.domain.runtime

/**
 * Puts the accessibility service back after the system took it away without
 * the user asking.
 *
 * A force stop removes an accessibility service from the enabled list, and a
 * HyperOS cleaner force-stops Babel every time it runs, so the user was back in
 * system settings by hand each time (`docs/systems/capabilities.md`). Writing
 * the list takes `WRITE_SECURE_SETTINGS`, which only `adb shell pm grant` can
 * give; without it, this does nothing and the user is sent to the switch.
 *
 * Implemented on the platform side, where the service is.
 */
interface AccessibilityRecovery {

    /** Whether the permission to write the list has been granted. */
    val canRestore: Boolean

    /**
     * Re-enables the service if the user had it on and something other than
     * the user took it off.
     *
     * @return whether a re-enable was written. Binding follows some seconds
     *   later, if at all.
     */
    fun restoreIfDropped(): Boolean
}
