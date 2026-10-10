package com.babel.data.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.domain.translation.TranslationCacheKey
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DurableTranslationCacheTest {

    private val dir = Files.createTempDirectory("babel-cache").toFile()
    private val file = File(dir, "labels.json")

    private fun key(text: String) =
        TranslationCacheKey(text, LanguageTag("ja"), LanguageTag("zh"), ProviderId("chat"))

    @Test
    fun `a durable entry survives a new cache`() = runBlocking {
        DurableTranslationCache(file).putDurable(key("レフ"), "雷夫")

        assertEquals("雷夫", DurableTranslationCache(file).get(key("レフ")))
    }

    @Test
    fun `an ordinary entry is not written`() = runBlocking {
        DurableTranslationCache(file).put(key("君の名前は……？"), "你的名字是？")

        assertNull(DurableTranslationCache(file).get(key("君の名前は……？")))
        assertFalse(file.exists())
    }

    @Test
    fun `clear deletes the file`() = runBlocking {
        val cache = DurableTranslationCache(file)
        cache.putDurable(key("レフ"), "雷夫")
        cache.clear()

        assertFalse(file.exists())
        assertNull(DurableTranslationCache(file).get(key("レフ")))
    }

    @Test
    fun `the oldest durable entries go first past the limit`() = runBlocking {
        val cache = DurableTranslationCache(file, maxDurable = 2)
        cache.putDurable(key("一"), "1")
        cache.putDurable(key("二"), "2")
        cache.putDurable(key("三"), "3")

        val again = DurableTranslationCache(file)
        assertNull(again.get(key("一")))
        assertEquals("3", again.get(key("三")))
    }
}
