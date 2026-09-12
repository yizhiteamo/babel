package com.babel.domain.language

import com.babel.core.model.LanguageTag
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.core.testing.FakeSystemLocaleProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class DefaultLanguageResolverTest {

    private val system = FakeSystemLocaleProvider(LanguageTag("en"))

    private fun resolver(
        supported: Collection<String> = listOf("en", "zh", "ja", "de"),
        fallback: String = "en",
    ) = DefaultLanguageResolver(
        systemLocaleProvider = system,
        supported = supported.map(::LanguageTag),
        fallbackTarget = LanguageTag(fallback),
    )

    @Test
    fun `auto detect leaves source null for the provider to decide`() {
        val pair = resolver().resolve(SourceLanguageMode.AutoDetect, TargetLanguageMode.FollowSystem)

        assertNull(pair.source)
    }

    @Test
    fun `target follows system language by default`() {
        system.tag = LanguageTag("ja")

        val pair = resolver().resolve(SourceLanguageMode.AutoDetect, TargetLanguageMode.FollowSystem)

        assertEquals(LanguageTag("ja"), pair.target)
    }

    @Test
    fun `target follows the system when it changes`() {
        val resolver = resolver()
        system.tag = LanguageTag("de")
        assertEquals(LanguageTag("de"), resolver.resolve(SourceLanguageMode.AutoDetect, TargetLanguageMode.FollowSystem).target)

        system.tag = LanguageTag("zh")
        assertEquals(LanguageTag("zh"), resolver.resolve(SourceLanguageMode.AutoDetect, TargetLanguageMode.FollowSystem).target)
    }

    @Test
    fun `manual overrides win over auto and system`() {
        system.tag = LanguageTag("en")

        val pair = resolver().resolve(
            SourceLanguageMode.Manual(LanguageTag("ja")),
            TargetLanguageMode.Manual(LanguageTag("de")),
        )

        assertEquals(LanguageTag("ja"), pair.source)
        assertEquals(LanguageTag("de"), pair.target)
    }

    @Test
    fun `chinese is not assumed to be the target`() {
        system.tag = LanguageTag("de")

        val pair = resolver().resolve(SourceLanguageMode.AutoDetect, TargetLanguageMode.FollowSystem)

        assertEquals(LanguageTag("de"), pair.target)
    }

    @Test
    fun `regional system locale resolves against a base-language provider`() {
        system.tag = LanguageTag("zh-Hans-CN")

        val pair = resolver(supported = listOf("en", "zh")).resolve(
            SourceLanguageMode.AutoDetect,
            TargetLanguageMode.FollowSystem,
        )

        assertEquals(LanguageTag("zh"), pair.target)
    }

    @Test
    fun `unsupported system language falls back instead of producing nothing`() {
        system.tag = LanguageTag("is")

        val pair = resolver(supported = listOf("en", "zh"), fallback = "en").resolve(
            SourceLanguageMode.AutoDetect,
            TargetLanguageMode.FollowSystem,
        )

        assertEquals(LanguageTag("en"), pair.target)
    }

    @Test
    fun `underscore locale form is normalized rather than becoming undetermined`() {
        val resolver = resolver()

        assertEquals(LanguageTag("zh-CN"), resolver.normalize(LanguageTag("zh_CN")))
    }

    @Test
    fun `normalize canonicalizes casing`() {
        val resolver = resolver()

        assertEquals(LanguageTag("zh-Hans"), resolver.normalize(LanguageTag("ZH-HANS")))
    }

    @Test
    fun `unparseable tag is preserved rather than silently becoming und`() {
        val resolver = resolver()
        val garbage = LanguageTag("!!not-a-tag!!")

        assertEquals(garbage, resolver.normalize(garbage))
    }

    @Test
    fun `support check accepts regional variants of a supported base language`() {
        val resolver = resolver(supported = listOf("en", "zh"))

        assertTrue(resolver.isSupported(LanguageTag("zh-Hant-TW")))
        assertFalse(resolver.isSupported(LanguageTag("ko")))
    }

    @Test
    fun `supported languages are reported normalized and sorted`() {
        val resolver = resolver(supported = listOf("ZH", "en", "JA"))

        assertEquals(
            listOf(LanguageTag("en"), LanguageTag("ja"), LanguageTag("zh")),
            resolver.supportedLanguages(),
        )
    }
}
