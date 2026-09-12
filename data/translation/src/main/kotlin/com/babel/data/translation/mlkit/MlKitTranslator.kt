package com.babel.data.translation.mlkit

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import com.babel.domain.translation.Translator
import com.google.mlkit.common.MlKitException
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import com.google.mlkit.nl.translate.Translator as MlKitClient

/**
 * On-device translation. Screen text never leaves the device, which is what
 * makes the local/remote boundary in `docs/systems/privacy.md` trivially
 * satisfiable for V1.
 *
 * Every ML Kit type stays inside this file — the domain sees only
 * [TranslationResult] and [TranslationError] (ADR 005).
 *
 * Clients are cached per language pair because `Translation.getClient` opens a
 * model handle; creating one per request would thrash on every scroll.
 *
 * [downloadConditions] defaults to unrestricted rather than Wi-Fi-only: a
 * Wi-Fi requirement makes translation fail on mobile data with no visible
 * reason, which reads as the feature being broken.
 */
class MlKitTranslator(
    private val downloadConditions: DownloadConditions = DownloadConditions.Builder().build(),
) : Translator, Closeable {

    override val id: ProviderId = PROVIDER_ID

    private val clients = ConcurrentHashMap<String, MlKitClient>()
    private val clientLock = Mutex()

    private val languageIdentifier by lazy { LanguageIdentification.getClient() }

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean {
        if (MlKitLanguages.toMlKitCode(target) == null) return false
        // A null source means auto-detect, which ML Kit handles itself.
        return source == null || MlKitLanguages.toMlKitCode(source) != null
    }

    override suspend fun translate(request: TranslationRequest): TranslationResult = try {
        translateOrThrow(request)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        request.failed(failure.toTranslationError())
    }

    private suspend fun translateOrThrow(request: TranslationRequest): TranslationResult {
        val targetCode = MlKitLanguages.toMlKitCode(request.languages.target)
            ?: return request.failed(
                TranslationError.Unsupported("no on-device model for target language"),
            )

        val declaredSource = request.languages.source
        val sourceCode = if (declaredSource != null) {
            MlKitLanguages.toMlKitCode(declaredSource)
                ?: return request.failed(
                    TranslationError.Unsupported("no on-device model for source language"),
                )
        } else {
            detectLanguage(request.sourceText)
                ?: return request.failed(TranslationError.Unsupported("language not recognised"))
        }

        if (sourceCode == targetCode) {
            return request.unchanged(LanguageTag(sourceCode))
        }

        val client = clientFor(sourceCode, targetCode)
        client.downloadModelIfNeeded(downloadConditions).await()
        val translated = client.translate(request.sourceText).await()

        return request.translated(translated, LanguageTag(sourceCode))
    }

    /** Returns null for text ML Kit cannot attribute to a supported language. */
    private suspend fun detectLanguage(text: String): String? {
        val detected = languageIdentifier.identifyLanguage(text).await()
        if (detected == UNDETERMINED) return null
        return MlKitLanguages.toMlKitCode(LanguageTag(detected))
    }

    private suspend fun clientFor(sourceCode: String, targetCode: String): MlKitClient {
        val key = "$sourceCode>$targetCode"
        clients[key]?.let { return it }

        return clientLock.withLock {
            clients.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceCode)
                        .setTargetLanguage(targetCode)
                        .build(),
                )
            }
        }
    }

    override fun close() {
        clients.values.forEach(MlKitClient::close)
        clients.clear()
        languageIdentifier.close()
    }

    // ------------------------------------------------------------ conversions

    private fun TranslationRequest.translated(
        text: String,
        detected: LanguageTag,
    ) = TranslationResult(
        requestId = requestId,
        elementId = elementId,
        revision = revision,
        originalText = sourceText,
        translatedText = text,
        detectedSourceLanguage = detected,
        provider = PROVIDER_ID,
        status = TranslationStatus.Translated,
    )

    private fun TranslationRequest.unchanged(detected: LanguageTag) = TranslationResult(
        requestId = requestId,
        elementId = elementId,
        revision = revision,
        originalText = sourceText,
        translatedText = sourceText,
        detectedSourceLanguage = detected,
        provider = PROVIDER_ID,
        status = TranslationStatus.Unchanged,
    )

    private fun TranslationRequest.failed(error: TranslationError) = TranslationResult(
        requestId = requestId,
        elementId = elementId,
        revision = revision,
        originalText = sourceText,
        translatedText = "",
        provider = PROVIDER_ID,
        status = TranslationStatus.Failed(error),
    )

    /**
     * Classification decides whether the coordinator retries, so a model that
     * is merely missing must not look like a transient network blip.
     */
    private fun Throwable.toTranslationError(): TranslationError = when {
        this !is MlKitException -> TranslationError.Unexpected(this::class.simpleName)

        errorCode == MlKitException.NETWORK_ISSUE -> TranslationError.Network(message)

        errorCode == MlKitException.UNAVAILABLE -> TranslationError.Offline

        errorCode == MlKitException.NOT_ENOUGH_SPACE ->
            TranslationError.ProviderRejected(PROVIDER_ID, "not enough space for the model")

        errorCode == MlKitException.MODEL_HASH_MISMATCH ->
            TranslationError.ProviderRejected(PROVIDER_ID, "model verification failed")

        errorCode == MlKitException.UNSUPPORTED ->
            TranslationError.Unsupported(message)

        else -> TranslationError.Unexpected("MlKitException($errorCode)")
    }

    private companion object {
        val PROVIDER_ID = ProviderId("mlkit")
        const val UNDETERMINED = "und"
    }
}
