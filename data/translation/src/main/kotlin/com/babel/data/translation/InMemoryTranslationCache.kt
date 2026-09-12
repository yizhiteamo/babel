package com.babel.data.translation

import com.babel.domain.translation.TranslationCache
import com.babel.domain.translation.TranslationCacheKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * LRU cache bounded by entry count.
 *
 * `docs/systems/cache.md` calls for starting simple, and in-memory is enough
 * for V1: entries are only useful while the source app is on screen, and not
 * persisting them keeps screen text off disk by default
 * (`docs/systems/privacy.md`).
 *
 * A [Mutex] rather than a synchronized block, because callers are coroutines
 * and must not block a dispatcher thread while another is evicting.
 *
 * Constructed through a Hilt module rather than `@Inject`, so [maxEntries] can
 * keep a default without needing an `Int` binding in the graph.
 */
class InMemoryTranslationCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) : TranslationCache {

    init {
        require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
    }

    private val mutex = Mutex()

    private val entries = object : LinkedHashMap<TranslationCacheKey, String>(
        INITIAL_CAPACITY,
        LOAD_FACTOR,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<TranslationCacheKey, String>?,
        ): Boolean = size > maxEntries
    }

    override suspend fun get(key: TranslationCacheKey): String? = mutex.withLock {
        entries[key]
    }

    override suspend fun put(key: TranslationCacheKey, translatedText: String) {
        mutex.withLock { entries[key] = translatedText }
    }

    override suspend fun clear() {
        mutex.withLock { entries.clear() }
    }

    suspend fun size(): Int = mutex.withLock { entries.size }

    companion object {
        const val DEFAULT_MAX_ENTRIES: Int = 500
        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
