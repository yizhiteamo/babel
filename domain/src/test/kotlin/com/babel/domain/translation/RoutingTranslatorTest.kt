package com.babel.domain.translation

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
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoutingTranslatorTest {

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

    private val local = Named("local")
    private val remote = Named("remote")
    private val settings = FakeSettingsRepository()

    private fun routing(scope: TestScope) =
        RoutingTranslator(local, remote, settings, scope.backgroundScope)

    private val request = TranslationRequest(
        requestId = RequestId("r"),
        elementId = TextElementId("e"),
        revision = Revision(0),
        sourceText = "はい",
        languages = LanguagePair(source = LanguageTag("ja"), target = LanguageTag("zh")),
    )

    private val configured = RemoteProviderSettings(
        endpoint = "https://example.invalid/v1/chat/completions",
        model = "some-model",
        apiKey = ApiKey("secret"),
    )

    /** The whole point of the default: nothing leaves the device unasked. */
    @Test
    fun `by default everything goes to the on-device translator`() =
        runTest(UnconfinedTestDispatcher()) {
            val translator = routing(this)

            assertEquals("local:はい", translator.translate(request).translatedText)
            assertEquals(ProviderId("local"), translator.id)
        }

    @Test
    fun `selecting the remote provider routes to it`() = runTest(UnconfinedTestDispatcher()) {
        val translator = routing(this)

        settings.setRemoteProvider(configured)
        settings.setProvider(RemoteProviderSettings.PROVIDER)

        assertEquals("remote:はい", translator.translate(request).translatedText)
    }

    /**
     * Selecting a provider with nowhere to send to would fail every request.
     * Staying on-device is both the safer answer and the working one.
     */
    @Test
    fun `selecting it without configuring it stays on-device`() =
        runTest(UnconfinedTestDispatcher()) {
            val translator = routing(this)

            settings.setProvider(RemoteProviderSettings.PROVIDER)

            assertEquals("local:はい", translator.translate(request).translatedText)
        }

    @Test
    fun `configuring it without selecting it stays on-device`() =
        runTest(UnconfinedTestDispatcher()) {
            val translator = routing(this)

            settings.setRemoteProvider(configured)

            assertEquals("local:はい", translator.translate(request).translatedText)
        }

    /**
     * A model running on the user's own machine wants no credential, and that
     * is the one configuration where the text never reaches the internet.
     * Requiring a key made the privacy-preserving option the unreachable one:
     * the dialog would not save it and this gate would not open (ADR 010).
     */
    @Test
    fun `a server that wants no key still routes`() = runTest(UnconfinedTestDispatcher()) {
        val translator = routing(this)

        settings.setRemoteProvider(
            RemoteProviderSettings(
                endpoint = "http://10.0.2.2:1234/v1/chat/completions",
                model = "some-local-model",
                apiKey = ApiKey(""),
            ),
        )
        settings.setProvider(RemoteProviderSettings.PROVIDER)

        assertEquals("remote:はい", translator.translate(request).translatedText)
    }

    /** What the gate is actually for: somewhere to send to. */
    @Test
    fun `a blank endpoint stays on-device however else it is filled in`() =
        runTest(UnconfinedTestDispatcher()) {
            val translator = routing(this)

            settings.setRemoteProvider(
                RemoteProviderSettings(
                    endpoint = "",
                    model = "some-model",
                    apiKey = ApiKey("secret"),
                ),
            )
            settings.setProvider(RemoteProviderSettings.PROVIDER)

            assertEquals("local:はい", translator.translate(request).translatedText)
        }

    /**
     * `TranslationCacheKey` carries the provider, so a translation made by one
     * engine must never be served for the other. Reporting the live route is
     * what makes that hold across a switch.
     */
    @Test
    fun `the reported provider follows the route`() = runTest(UnconfinedTestDispatcher()) {
        val translator = routing(this)
        assertEquals(ProviderId("local"), translator.id)

        settings.setRemoteProvider(configured)
        settings.setProvider(RemoteProviderSettings.PROVIDER)
        assertEquals(ProviderId("remote"), translator.id)

        settings.setProvider(null)
        assertEquals(ProviderId("local"), translator.id)
    }
}
