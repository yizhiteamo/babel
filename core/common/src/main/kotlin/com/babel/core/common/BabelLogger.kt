package com.babel.core.common

/**
 * Logging seam. The domain stays free of `android.util.Log` so it remains a
 * pure-Kotlin module and testable off-device.
 *
 * Never pass raw screen text to these methods; pass [Redact.text] instead.
 */
interface BabelLogger {
    fun debug(tag: String, message: String)
    fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    object NoOp : BabelLogger {
        override fun debug(tag: String, message: String) = Unit
        override fun info(tag: String, message: String) = Unit
        override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
        override fun error(tag: String, message: String, throwable: Throwable?) = Unit
    }
}
