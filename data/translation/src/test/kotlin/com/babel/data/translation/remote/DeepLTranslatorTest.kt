package com.babel.data.translation.remote

import com.babel.core.model.ApiKey
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationStatus
import com.babel.core.testing.FakeSettingsRepository
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The parts that can be wrong without a network being involved.
 *
 * What goes over the wire is checked on a device against a real key, because a
 * fake server would only confirm that this sends what this thinks it sends. The
 * rules below are the ones that silently produce a 400 or a wrong translation,
 * and they are pure functions.
 */
class DeepLTranslatorTest {

    private val settings = FakeSettingsRepository()
    private val translator = DeepLTranslator(settings)

    private fun request(source: LanguageTag?, target: LanguageTag) = TranslationRequest(
        requestId = RequestId("r"),
        elementId = TextElementId("e"),
        revision = Revision(0),
        sourceText = "はい",
        languages = LanguagePair(source = source, target = target),
    )

    /**
     * The rule that lets a user paste a key and nothing else. A free key sent
     * to the paid host fails as 403, which reads as a bad key rather than as a
     * bad address.
     */
    @Test
    fun `a free-tier key picks the free host`() {
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            DeepLTranslator.hostFor("00000000-0000-0000-0000-000000000000:fx"),
        )
        assertEquals(
            "https://api.deepl.com/v2/translate",
            DeepLTranslator.hostFor("00000000-0000-0000-0000-000000000000"),
        )
    }

    /** Pasting a key usually brings whitespace with it. */
    @Test
    fun `a key with stray whitespace still picks the free host`() {
        assertEquals(
            "https://api-free.deepl.com/v2/translate",
            DeepLTranslator.hostFor("  abc:fx\n"),
        )
    }

    /**
     * `LanguageResolver` narrows a device tag to what a provider accepts; this
     * is the last step of that for DeepL, which has nowhere to put a script or
     * a region.
     */
    @Test
    fun `a regional tag narrows to the language DeepL knows`() {
        assertEquals("ZH", DeepLTranslator.targetCodeFor(LanguageTag("zh-Hans-CN")))
        assertEquals("ZH", DeepLTranslator.targetCodeFor(LanguageTag("zh")))
        assertEquals("JA", DeepLTranslator.targetCodeFor(LanguageTag("ja")))
    }

    /**
     * English and Portuguese have no unqualified target — DeepL wants the
     * variant — so a device set to plain `en` must not be sent `EN`.
     */
    @Test
    fun `targets that require a variant get one`() {
        assertEquals("EN-US", DeepLTranslator.targetCodeFor(LanguageTag("en")))
        assertEquals("EN-US", DeepLTranslator.targetCodeFor(LanguageTag("en-GB")))
        assertEquals("PT-PT", DeepLTranslator.targetCodeFor(LanguageTag("pt-BR")))
    }

    /** Sources take no variant, so the narrowing is the whole of it. */
    @Test
    fun `sources are the bare language`() {
        assertEquals("EN", DeepLTranslator.sourceCodeFor(LanguageTag("en-GB")))
        assertEquals("JA", DeepLTranslator.sourceCodeFor(LanguageTag("ja")))
        assertNull(DeepLTranslator.sourceCodeFor(LanguageTag("th")))
    }

    /**
     * Declining here costs one request less than learning it from a 400 — and
     * an unknown source is not a reason to decline, since detecting it is what
     * DeepL does when the field is left out.
     */
    @Test
    fun `supports answers from the list DeepL actually has`() {
        assertTrue(translator.supports(LanguageTag("ja"), LanguageTag("zh")))
        assertTrue(translator.supports(null, LanguageTag("zh")))
        assertFalse(translator.supports(LanguageTag("ja"), LanguageTag("th")))
        assertFalse(translator.supports(LanguageTag("th"), LanguageTag("zh")))
    }

    /**
     * A provider failure degrades one element, never the pipeline: the contract
     * is that `translate` returns a `Failed` result rather than throwing.
     */
    @Test
    fun `an unconfigured provider fails the request instead of throwing`() = runTest {
        val result = translator.translate(request(LanguageTag("ja"), LanguageTag("zh")))

        assertTrue(result.status is TranslationStatus.Failed)
        assertEquals(RemoteService.DEEPL.providerId, result.provider)
    }

    /** Same again for a pair DeepL cannot take, and without spending a call. */
    @Test
    fun `an unsupported target fails the request`() = runTest {
        settings.setRemoteProvider(
            RemoteProviderSettings(
                service = RemoteService.DEEPL,
                apiKey = ApiKey("abc:fx"),
            ),
        )

        val result = translator.translate(request(LanguageTag("ja"), LanguageTag("th")))

        assertTrue(result.status is TranslationStatus.Failed)
    }

    /**
     * Cache keys carry the provider, so this must not be the chat route's id —
     * switching services would otherwise serve one engine's translation for the
     * other's request.
     */
    @Test
    fun `it files its translations under its own provider`() {
        assertEquals(RemoteService.DEEPL.providerId, translator.id)
        assertTrue(translator.id != RemoteService.CHAT.providerId)
    }
}
