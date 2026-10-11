package com.babel.domain.translation

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class SameLanguageGuardTest {

    private var calls = 0

    private val inner = object : Translator {
        override val id = ProviderId("fake")
        override fun supports(source: LanguageTag?, target: LanguageTag) = true
        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            return TranslationResult(
                request.requestId, request.elementId, request.revision, request.sourceText, "译文",
                provider = id, status = TranslationStatus.Translated,
            )
        }
    }

    private fun request(text: String, source: String? = null, target: String = "zh") = TranslationRequest(
        RequestId("r"), TextElementId("e"), Revision(1), text,
        LanguagePair(source?.let(::LanguageTag), LanguageTag(target)),
    )

    @Test
    fun `chinese text for a chinese reader is left as it is`() = runBlocking {
        val result = SameLanguageGuard(inner).translate(request("[16P]一人之下"))

        assertEquals(TranslationStatus.Unchanged, result.status)
        assertEquals(0, calls)
    }

    @Test
    fun `japanese with kana is translated`() = runBlocking {
        SameLanguageGuard(inner).translate(request("お気をつけて"))

        assertEquals(1, calls)
    }

    @Test
    fun `kanji said to be japanese is translated`() = runBlocking {
        SameLanguageGuard(inner).translate(request("風紀委員会", source = "ja"))

        assertEquals(1, calls)
    }

    @Test
    fun `another target is not affected`() = runBlocking {
        SameLanguageGuard(inner).translate(request("历史", target = "en"))

        assertEquals(1, calls)
    }

    @Test
    fun `a source said to be the target is left as it is`() = runBlocking {
        val result = SameLanguageGuard(inner).translate(request("[16P]一人之下", source = "zh", target = "zh-Hans"))

        assertEquals(TranslationStatus.Unchanged, result.status)
        assertEquals(0, calls)
    }

    @Test
    fun `an answer still in japanese is asked again without context`() = runBlocking {
        val answers = ArrayDeque(listOf("ちょっと困って", "有点困扰"))
        val contexts = mutableListOf<String?>()
        val echoing = object : Translator {
            override val id = ProviderId("fake")
            override fun supports(source: LanguageTag?, target: LanguageTag) = true
            override suspend fun translate(request: TranslationRequest): TranslationResult {
                contexts += request.context
                return TranslationResult(
                    request.requestId, request.elementId, request.revision, request.sourceText, answers.removeFirst(),
                    provider = id, status = TranslationStatus.Translated,
                )
            }
        }

        val result = SameLanguageGuard(echoing).translate(request("ちょとこまって").copy(context = "いま"))

        assertEquals("有点困扰", result.translatedText)
        assertEquals(listOf("いま", null), contexts)
    }
}
