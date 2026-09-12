package com.babel.data.translation.mlkit

import com.babel.core.model.LanguageTag
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import com.babel.core.testing.FakeSystemLocaleProvider
import com.babel.domain.language.DefaultLanguageResolver
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The mapping is the part of the ML Kit integration with real edge cases, and
 * it runs without an on-device model — unlike translation itself, which needs
 * an instrumented test.
 */
class MlKitLanguagesTest {

    @Test
    fun `maps a plain tag`() {
        assertEquals("en", MlKitLanguages.toMlKitCode(LanguageTag("en")))
    }

    /**
     * ML Kit rejects anything beyond the bare language subtag. This is not a
     * bug to work around here: narrowing a tag is locale policy, which belongs
     * to [DefaultLanguageResolver] — see the test below.
     */
    @Test
    fun `script and region qualified tags are rejected outright`() {
        assertNull(MlKitLanguages.toMlKitCode(LanguageTag("zh-Hans-CN")))
        assertNull(MlKitLanguages.toMlKitCode(LanguageTag("pt-BR")))
    }

    @Test
    fun `unsupported language has no code`() {
        assertNull(MlKitLanguages.toMlKitCode(LanguageTag("xx")))
    }

    @Test
    fun `supported list includes common languages`() {
        val supported = MlKitLanguages.supportedTags().map { it.value }.toSet()

        assertTrue(supported.isNotEmpty())
        assertTrue(supported.containsAll(listOf("en", "zh", "ja", "de")))
    }

    /**
     * The pipeline's real requirement: a device reporting `zh-Hans-CN` must end
     * up translating into a language ML Kit actually has a model for. The
     * resolver narrows the tag, so the translator never sees the regional form.
     */
    @Test
    fun `resolver narrows a device locale to a code ML Kit accepts`() {
        val resolver = DefaultLanguageResolver(
            systemLocaleProvider = FakeSystemLocaleProvider(LanguageTag("zh-Hans-CN")),
            supported = MlKitLanguages.supportedTags(),
        )

        val target = resolver.resolve(
            SourceLanguageMode.AutoDetect,
            TargetLanguageMode.FollowSystem,
        ).target

        assertEquals(LanguageTag("zh"), target)
        assertEquals("zh", MlKitLanguages.toMlKitCode(target))
    }
}
