package com.babel.domain.translation

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FragmentingTranslatorTest {

    /** Answers with a fixed mapping, and records every string it was given. */
    private class Recording(
        private val answers: Map<String, String> = emptyMap(),
        private val status: (String) -> TranslationStatus = { TranslationStatus.Translated },
    ) : Translator {
        val seen = mutableListOf<String>()
        override val id = ProviderId("fake")
        override fun supports(source: LanguageTag?, target: LanguageTag) = true

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            seen += request.sourceText
            return TranslationResult(
                requestId = request.requestId,
                elementId = request.elementId,
                revision = request.revision,
                originalText = request.sourceText,
                translatedText = answers[request.sourceText] ?: "<${request.sourceText}>",
                detectedSourceLanguage = LanguageTag("ja"),
                provider = id,
                status = status(request.sourceText),
            )
        }
    }

    private fun request(text: String) = TranslationRequest(
        requestId = RequestId("r"),
        elementId = TextElementId("e"),
        revision = Revision(1),
        sourceText = text,
        languages = LanguagePair(source = LanguageTag("ja"), target = LanguageTag("zh")),
    )

    @Test
    fun `text without ellipses goes straight through`() = runTest {
        val delegate = Recording()

        val result = FragmentingTranslator(delegate).translate(request("いくらでも使ってください"))

        assertEquals(listOf("いくらでも使ってください"), delegate.seen)
        assertEquals("<いくらでも使ってください>", result.translatedText)
    }

    @Test
    fun `each fragment is translated on its own`() = runTest {
        val delegate = Recording(mapOf("目を" to "眼睛", "閉じて" to "闭上"))

        val result = FragmentingTranslator(delegate).translate(request("目を…閉じて…"))

        assertEquals(listOf("目を", "閉じて"), delegate.seen)
        assertEquals("眼睛…闭上…", result.translatedText)
    }

    /** The measured case: the whole line came back as dots without the word. */
    @Test
    fun `a leading ellipsis keeps its place`() = runTest {
        val delegate = Recording(mapOf("あ" to "啊"))

        val result = FragmentingTranslator(delegate).translate(request("…あ"))

        assertEquals(listOf("あ"), delegate.seen)
        assertEquals("…啊", result.translatedText)
    }

    @Test
    fun `the whole original is reported back, not the last fragment`() = runTest {
        val result = FragmentingTranslator(Recording()).translate(request("あ…い"))

        assertEquals("あ…い", result.originalText)
        assertEquals(Revision(1), result.revision)
        assertEquals(ProviderId("fake"), result.provider)
    }

    /**
     * Half a translated bubble is worse than none, and the caller decides what
     * a failure means — so the failure is passed on rather than papered over.
     */
    @Test
    fun `one fragment failing fails the line`() = runTest {
        val delegate = Recording(
            status = { text ->
                if (text == "い") TranslationStatus.Failed(TranslationError.Offline)
                else TranslationStatus.Translated
            },
        )

        val result = FragmentingTranslator(delegate).translate(request("あ…い…う"))

        assertTrue(result.status is TranslationStatus.Failed)
        assertEquals("あ…い…う", result.originalText)
        assertEquals("", result.translatedText)
    }

    /** A fragment already in the target language stays as it was written. */
    @Test
    fun `unchanged fragments are kept verbatim`() = runTest {
        val delegate = Recording(
            answers = mapOf("あ" to "啊"),
            status = { text -> if (text == "い") TranslationStatus.Unchanged else TranslationStatus.Translated },
        )

        val result = FragmentingTranslator(delegate).translate(request("あ…い"))

        assertEquals("啊…い", result.translatedText)
        assertEquals(TranslationStatus.Translated, result.status)
    }

    /** Nothing needed translating, so there is nothing to draw over the art. */
    @Test
    fun `a line that needs no translation reports unchanged`() = runTest {
        val delegate = Recording(status = { TranslationStatus.Unchanged })

        val result = FragmentingTranslator(delegate).translate(request("あ…い"))

        assertEquals(TranslationStatus.Unchanged, result.status)
    }

    @Test
    fun `the provider id is the provider's own, so cache keys are unaffected`() {
        val delegate = Recording()

        assertEquals(delegate.id, FragmentingTranslator(delegate).id)
    }
}
