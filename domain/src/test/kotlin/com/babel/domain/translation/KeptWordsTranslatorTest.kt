package com.babel.domain.translation

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class KeptWordsTranslatorTest {

    private val seen = mutableListOf<String>()

    /** Translates by swapping 「ちゃん。」 for 「酱。」 and leaving everything else. */
    private val inner = object : Translator {
        override val id = ProviderId("fake")
        override fun supports(source: LanguageTag?, target: LanguageTag) = true
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            seen += request.sourceText
            return TranslationResult(
                request.requestId, request.elementId, request.revision, request.sourceText,
                request.sourceText.replace("ちゃん。", "酱。"), provider = id, status = TranslationStatus.Translated,
            )
        }
    }

    private fun request(text: String, type: TextSourceType = TextSourceType.GAME) = TranslationRequest(
        RequestId("r"), TextElementId("e"), Revision(1), text,
        LanguagePair(LanguageTag("ja"), LanguageTag("zh")), sourceType = type,
    )

    @Test
    fun `a player's name goes as a mark and comes back`() = runBlocking {
        val result = KeptWordsTranslator(inner).translate(request("yizhiteamoちゃん。"))

        assertEquals(listOf("{1}ちゃん。"), seen)
        assertEquals("yizhiteamo酱。", result.translatedText)
    }

    @Test
    fun `an English line is sent as it is`() = runBlocking {
        KeptWordsTranslator(inner).translate(request("Where are you going?"))

        assertEquals(listOf("Where are you going?"), seen)
    }

    @Test
    fun `text from another app is sent as it is`() = runBlocking {
        KeptWordsTranslator(inner).translate(request("yizhiteamoちゃん。", TextSourceType.ACCESSIBILITY))

        assertEquals(listOf("yizhiteamoちゃん。"), seen)
    }

    @Test
    fun `a name the answer dropped from the start is put back`() = runBlocking {
        val dropping = object : Translator by inner {
            override suspend fun translate(request: TranslationRequest) = inner.translate(request).let {
                it.copy(translatedText = "紧急事态。")
            }
        }

        assertEquals("yizhiteamo，紧急事态。", KeptWordsTranslator(dropping).translate(request("yizhiteamo。緊急事態という事で")).translatedText)
    }
}
