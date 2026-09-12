package com.babel.app.logging

import android.util.Log
import com.babel.app.BuildConfig
import com.babel.core.common.BabelLogger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every tag is prefixed so the whole pipeline can be filtered with one logcat
 * expression.
 *
 * Debug logging is dropped in release builds: it is the chattiest level and the
 * one most likely to carry something derived from screen content, even
 * redacted.
 */
@Singleton
class AndroidLogger @Inject constructor() : BabelLogger {

    override fun debug(tag: String, message: String) {
        if (BuildConfig.DEBUG) Log.d(prefixed(tag), message)
    }

    override fun info(tag: String, message: String) {
        Log.i(prefixed(tag), message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w(prefixed(tag), message, throwable)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e(prefixed(tag), message, throwable)
    }

    private fun prefixed(tag: String) = "$PREFIX$tag"

    private companion object {
        const val PREFIX = "Babel."
    }
}
