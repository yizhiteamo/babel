package com.babel.data.translation

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.domain.translation.TranslationCacheKey
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class InMemoryTranslationCacheTest {

    private val provider = ProviderId("mlkit")
    private val enToZh = LanguagePair(LanguageTag("en"), LanguageTag("zh"))

    private fun key(
        text: String,
        languages: LanguagePair = enToZh,
        provider: ProviderId = this.provider,
    ) = TranslationCacheKey.of(text, languages, provider)

    @Test
    fun `stores and returns a translation`() = runTest {
        val cache = InMemoryTranslationCache()
        cache.put(key("Hello"), "你好")

        assertEquals("你好", cache.get(key("Hello")))
    }

    @Test
    fun `missing entry returns null`() = runTest {
        assertNull(InMemoryTranslationCache().get(key("Hello")))
    }

    @Test
    fun `same text under a different target language is a separate entry`() = runTest {
        val cache = InMemoryTranslationCache()
        cache.put(key("Hello"), "你好")

        val toJapanese = key("Hello", LanguagePair(LanguageTag("en"), LanguageTag("ja")))

        assertNull(cache.get(toJapanese))
    }

    @Test
    fun `same text from a different provider is a separate entry`() = runTest {
        val cache = InMemoryTranslationCache()
        cache.put(key("Hello"), "你好")

        assertNull(cache.get(key("Hello", provider = ProviderId("other"))))
    }

    @Test
    fun `same text with a different source language is a separate entry`() = runTest {
        val cache = InMemoryTranslationCache()
        cache.put(key("Hello"), "你好")

        val autoDetected = key("Hello", LanguagePair(null, LanguageTag("zh")))

        assertNull(cache.get(autoDetected))
    }

    @Test
    fun `whitespace-only differences hit the same entry`() = runTest {
        val cache = InMemoryTranslationCache()
        cache.put(key("The quick brown fox"), "敏捷的棕色狐狸")

        assertEquals("敏捷的棕色狐狸", cache.get(key("  The quick\n  brown   fox  ")))
    }

    @Test
    fun `evicts the least recently used entry when full`() = runTest {
        val cache = InMemoryTranslationCache(maxEntries = 2)
        cache.put(key("one"), "1")
        cache.put(key("two"), "2")

        // Touch "one" so "two" becomes the least recently used.
        cache.get(key("one"))
        cache.put(key("three"), "3")

        assertEquals(2, cache.size())
        assertEquals("1", cache.get(key("one")))
        assertNull(cache.get(key("two")))
        assertEquals("3", cache.get(key("three")))
    }

    @Test
    fun `clear empties the cache`() = runTest {
        val cache = InMemoryTranslationCache()
        cache.put(key("Hello"), "你好")
        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get(key("Hello")))
    }
}
