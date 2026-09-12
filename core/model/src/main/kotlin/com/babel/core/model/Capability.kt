package com.babel.core.model

/** A platform capability the translator needs in order to run. */
enum class Capability {
    ACCESSIBILITY_SERVICE,
    OVERLAY_WINDOW,
}

enum class CapabilityStatus {
    AVAILABLE,

    /** Present on the device but not granted by the user yet. */
    NOT_GRANTED,

    /** Not offered by this device or OS version. */
    UNSUPPORTED,

    UNKNOWN,
}

/**
 * Snapshot of every capability the current phase needs. Checks are centralized
 * here rather than duplicated across UI, services, and repositories.
 */
data class CapabilityState(
    val statuses: Map<Capability, CapabilityStatus> = emptyMap(),
) {
    operator fun get(capability: Capability): CapabilityStatus =
        statuses[capability] ?: CapabilityStatus.UNKNOWN

    val allAvailable: Boolean
        get() = statuses.isNotEmpty() && statuses.values.all { it == CapabilityStatus.AVAILABLE }

    val missing: List<Capability>
        get() = Capability.entries.filter { this[it] != CapabilityStatus.AVAILABLE }
}
