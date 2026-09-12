package com.babel.core.common

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Injected so tests can substitute a deterministic scheduler instead of real
 * threads. Provider calls and text normalization must never run on [main].
 */
interface DispatcherProvider {
    val main: CoroutineDispatcher
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
    override val main: CoroutineDispatcher get() = Dispatchers.Main
    override val default: CoroutineDispatcher get() = Dispatchers.Default
    override val io: CoroutineDispatcher get() = Dispatchers.IO
}
