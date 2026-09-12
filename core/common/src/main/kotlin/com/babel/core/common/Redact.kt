package com.babel.core.common

/**
 * Diagnostics must not contain user-visible screen text by default
 * (`docs/systems/privacy.md`). Log a redacted descriptor instead of the text.
 */
object Redact {
    /** e.g. `text(len=42, hash=1f3a9c04)` — stable enough to correlate, not to read. */
    fun text(value: String?): String {
        if (value == null) return "text(null)"
        val hash = value.hashCode().toUInt().toString(16).padStart(8, '0')
        return "text(len=${value.length}, hash=$hash)"
    }
}
