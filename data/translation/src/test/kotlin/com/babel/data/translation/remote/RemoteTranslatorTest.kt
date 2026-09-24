package com.babel.data.translation.remote

import com.babel.core.model.ApiKey
import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import com.babel.core.testing.FakeSettingsRepository
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import com.babel.domain.translation.Translator
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteTranslatorTest {

    private class Named(name: String) : Translator {
        override val id = ProviderId(name)
        override fun supports(source: LanguageTag?, target: LanguageTag) = true
        override suspend fun translate(request: TranslationRequest) = TranslationResult(
            requestId = request.requestId,
            elementId = request.elementId,
            revision = request.revision,
            originalText = request.sourceText,
            translatedText = "${id.value}:${request.sourceText}",
            provider = id,
            status = TranslationStatus.Translated,
        )
    }

    private val chat = Named("chat")
    private val deepL = Named("deepl")
    private val settings = FakeSettingsRepository()

    private fun dispatcher(scope: TestScope) =
        RemoteTranslator(chat, deepL, settings, scope.backgroundScope)

    private val request = TranslationRequest(
        requestId = RequestId("r"),
        elementId = TextElementId("e"),
        revision = Revision(0),
        sourceText = "はい",
        languages = LanguagePair(source = LanguageTag("ja"), target = LanguageTag("zh")),
    )

    /** What every configuration predating the choice is. */
    @Test
    fun `the chat endpoint is the default service`() = runTest(UnconfinedTestDispatcher()) {
        val translator = dispatcher(this)

        assertEquals("chat:はい", translator.translate(request).translatedText)
    }

    @Test
    fun `choosing DeepL sends the text there instead`() = runTest(UnconfinedTestDispatcher()) {
        val translator = dispatcher(this)

        settings.setRemoteProvider(
            RemoteProviderSettings(service = RemoteService.DEEPL, deepLKey = ApiKey("abc:fx")),
        )

        assertEquals("deepl:はい", translator.translate(request).translatedText)
    }

    /**
     * `TranslationCacheKey` carries the provider. If both services reported one
     * id, switching between them would serve a DeepL translation for a request
     * the chat route would have answered differently — silently, and only for
     * text that happened to be cached.
     */
    @Test
    fun `the reported provider follows the service`() = runTest(UnconfinedTestDispatcher()) {
        val translator = dispatcher(this)
        assertEquals(ProviderId("chat"), translator.id)

        settings.setRemoteProvider(
            RemoteProviderSettings(service = RemoteService.DEEPL, deepLKey = ApiKey("abc:fx")),
        )
        assertEquals(ProviderId("deepl"), translator.id)

        settings.setRemoteProvider(
            RemoteProviderSettings(
                service = RemoteService.CHAT,
                endpoint = "https://example.invalid/v1/chat/completions",
                model = "some-model",
            ),
        )
        assertEquals(ProviderId("chat"), translator.id)
    }
}
