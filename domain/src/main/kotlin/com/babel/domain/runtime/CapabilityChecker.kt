package com.babel.domain.runtime

import com.babel.core.model.Capability
import com.babel.core.model.CapabilityState
import kotlinx.coroutines.flow.StateFlow

/**
 * Answers "can the system perform this operation?" — separate from runtime
 * state, which answers "what is it doing right now?"
 *
 * Implemented on the platform side; checks live here only, never inlined into
 * UI or services.
 */
interface CapabilityChecker {
    val state: StateFlow<CapabilityState>

    /** Re-reads platform permissions, e.g. after returning from system settings. */
    fun refresh()

    /** Capabilities the active phase requires. */
    fun required(): Set<Capability>
}
