package com.babel.core.testing

import com.babel.domain.translation.TranslationCache
import com.babel.domain.translation.TranslationCacheKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Counts hits and misses so tests can prove cache keys actually separate —
 * the same sentence under a different target language or provider must miss.
 */
class FakeTranslationCache : TranslationCache {

    private val entries = ConcurrentHashMap<TranslationCacheKey, String>()

    val lookups: MutableList<TranslationCacheKey> = CopyOnWriteArrayList()
    val writes: MutableList<TranslationCacheKey> = CopyOnWriteArrayList()

    var hits: Int = 0
        private set

    var misses: Int = 0
        private set

    val size: Int get() = entries.size

    override suspend fun get(key: TranslationCacheKey): String? {
        lookups += key
        val value = entries[key]
        if (value == null) misses++ else hits++
        return value
    }

    override suspend fun put(key: TranslationCacheKey, translatedText: String) {
        writes += key
        entries[key] = translatedText
    }

    override suspend fun clear() {
        entries.clear()
    }

    /** Pre-seed an entry without recording it as a write. */
    fun seed(key: TranslationCacheKey, translatedText: String) {
        entries[key] = translatedText
    }

    fun reset() {
        entries.clear()
        lookups.clear()
        writes.clear()
        hits = 0
        misses = 0
    }
}
