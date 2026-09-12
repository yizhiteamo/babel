package com.babel.core.testing

import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope

/**
 * Routes every dispatcher to the test scheduler, so virtual time controls the
 * pipeline and `advanceTimeBy` can step through in-flight translations.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TestDispatcherProvider(
    private val dispatcher: TestDispatcher,
) : DispatcherProvider {
    constructor(scope: TestScope) : this(scope.testScheduler.let {
        kotlinx.coroutines.test.StandardTestDispatcher(it)
    })

    override val main: CoroutineDispatcher get() = dispatcher
    override val default: CoroutineDispatcher get() = dispatcher
    override val io: CoroutineDispatcher get() = dispatcher
}

/**
 * Keeps every log line so a test can assert that raw screen text never reaches
 * diagnostics — see `docs/systems/privacy.md`.
 */
class RecordingLogger : BabelLogger {

    data class Entry(val level: String, val tag: String, val message: String)

    val entries: MutableList<Entry> = CopyOnWriteArrayList()

    val messages: List<String> get() = entries.map { it.message }

    override fun debug(tag: String, message: String) {
        entries += Entry("debug", tag, message)
    }

    override fun info(tag: String, message: String) {
        entries += Entry("info", tag, message)
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        entries += Entry("warn", tag, message)
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        entries += Entry("error", tag, message)
    }

    /** True when no recorded line contains [text] verbatim. */
    fun neverLogged(text: String): Boolean = entries.none { it.message.contains(text) }

    fun reset() = entries.clear()
}
