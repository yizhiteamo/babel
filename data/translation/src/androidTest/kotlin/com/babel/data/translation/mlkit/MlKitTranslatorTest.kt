package com.babel.data.translation.mlkit

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.RequestId
import com.babel.core.model.Revision
import com.babel.core.model.TextElementId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Proves the provider choice actually holds on the target device.
 *
 * The emulator in use has no Google Play Services at all. `com.google.mlkit:*`
 * is the standalone distribution and should not need it at runtime, but the
 * first translation downloads a model over the network — so this is the test
 * that decides whether on-device translation is viable here.
 *
 * Real network and real model download, so timeouts are generous.
 */
@RunWith(AndroidJUnit4::class)
class MlKitTranslatorTest {

    private val translator = MlKitTranslator()

    @After
    fun tearDown() {
        translator.close()
    }

    private fun request(
        text: String,
        source: String?,
        target: String,
    ) = TranslationRequest(
        requestId = RequestId("androidTest"),
        elementId = TextElementId("e1"),
        revision = Revision(0),
        sourceText = text,
        languages = LanguagePair(source?.let(::LanguageTag), LanguageTag(target)),
    )

    @Test
    fun translatesEnglishToChineseOnDevice() = runBlocking {
        val result = withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
            translator.translate(request("Good morning", source = "en", target = "zh"))
        }

        assertEquals(
            TranslationStatus.Translated,
            result.status,
            "on-device translation failed: ${result.status}",
        )
        assertTrue(result.translatedText.isNotBlank(), "translated text was blank")
        assertTrue(
            result.translatedText != "Good morning",
            "output was identical to the input, so nothing was translated",
        )
    }

    @Test
    fun detectsSourceLanguageWhenNotDeclared() = runBlocking {
        val result = withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
            translator.translate(request("Good morning", source = null, target = "zh"))
        }

        assertEquals(TranslationStatus.Translated, result.status, "detection path failed")
        assertEquals(LanguageTag("en"), result.detectedSourceLanguage)
    }

    @Test
    fun sameLanguagePairIsReportedUnchanged() = runBlocking {
        val result = withTimeout(MODEL_DOWNLOAD_TIMEOUT_MS) {
            translator.translate(request("Good morning", source = "en", target = "en"))
        }

        assertEquals(TranslationStatus.Unchanged, result.status)
    }

    private companion object {
        const val MODEL_DOWNLOAD_TIMEOUT_MS = 180_000L
    }
}
