package com.babel.data.translation.remote

import com.babel.core.common.BabelLogger
import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import com.babel.domain.settings.RemoteProviderSettings
import com.babel.domain.settings.RemoteService
import com.babel.domain.settings.SettingsRepository
import com.babel.domain.translation.Translator
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Translates through DeepL's translation API.
 *
 * ## Why a second remote shape, when ADR 010 said one was enough
 *
 * Because a comic balloon is short, and short input is where an
 * instruction-following model stops following instructions. Measured on this
 * project's own pages, a chat model answered a two-character balloon with
 * laughter and replied to another with "I do not translate Japanese"
 * (`docs/milestones/v2.md`). That finding is what chose opus-mt over a local
 * LLM, on the grounds that a narrow translator "cannot refuse or ramble".
 *
 * This is that same reasoning reaching the network: a translation API has no
 * instruction to disobey. It also asks the user for less — one key, no model
 * name, no address.
 *
 * ## What it does not do
 *
 * No instruction. A chat model can be told these are comic balloons; this
 * cannot, and will read a line of dialogue as a standalone sentence. Which of
 * the two wins on a given page is a measurement rather than a principle, so
 * both stay.
 *
 * It does take a `context` — surrounding text it reads but does not translate,
 * and does not bill for. The payload carries it whenever a request has one.
 * Nothing in the pipeline sets one yet; whether anything should is what
 * `BalloonContextExperimentTest` is measuring.
 *
 * Text reaching this class has already passed the privacy policy — that is what
 * the coordinator checks before any provider is called
 * (`docs/systems/privacy.md`).
 */
class DeepLTranslator(
    private val settingsRepository: SettingsRepository,
    private val logger: BabelLogger = BabelLogger.NoOp,
    private val client: OkHttpClient = ChatTranslator.defaultClient(),
    /** Overridden in tests; production derives it from the key. See [hostFor]. */
    private val endpointOverride: String? = null,
) : Translator {

    override val id: ProviderId = RemoteService.DEEPL.providerId

    /**
     * A fixed list, unlike the chat route.
     *
     * DeepL translates between the pairs it supports and rejects the rest, so
     * declining here costs one request less than learning it from a 400. The
     * source side may be unknown — detection is DeepL's job when it is.
     */
    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean =
        targetCodeFor(target) != null && (source == null || sourceCodeFor(source) != null)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val settings = settingsRepository.settings.first().remote
        if (!settings.isConfigured) {
            return request.failed(
                TranslationError.ProviderRejected(id, "DeepL is not configured"),
            )
        }

        val target = targetCodeFor(request.languages.target)
            ?: return request.failed(
                TranslationError.ProviderRejected(id, "unsupported target language"),
            )

        return try {
            val started = System.currentTimeMillis()
            val translated = send(settings, request, target)
            // How long the remote leg takes is the one figure that moves when
            // the engine changes, and the page-latency budget is tracked to the
            // hundred milliseconds (`docs/milestones/v2.md`). Timing only: no
            // source text, no translation, no response body.
            logger.debug(TAG, "translated in ${System.currentTimeMillis() - started}ms")
            when {
                translated.isBlank() ->
                    request.failed(TranslationError.ProviderRejected(id, "empty response"))

                // Already in the target language; an overlay would only cover
                // the original with itself.
                translated == request.sourceText -> request.completed(
                    text = request.sourceText,
                    status = TranslationStatus.Unchanged,
                )

                else -> request.completed(translated, TranslationStatus.Translated)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (rejected: HttpStatus) {
            // The status, and only the status. It is the difference
            // between a bad key (403), a spent quota (456) and a
            // malformed request (400) — worth knowing, and knowable
            // without the body, which echoes the text back.
            logger.warn(TAG, "DeepL translation rejected: HTTP ${rejected.code}")
            request.failed(TranslationError.Network("HTTP ${rejected.code}"))
        } catch (failure: IOException) {
            // Status only, never the body: a DeepL rejection echoes the text it
            // was given, which is screen content and stays out of diagnostics.
            logger.warn(TAG, "DeepL translation failed: ${failure.javaClass.simpleName}")
            request.failed(TranslationError.Network(failure.javaClass.simpleName))
        } catch (unexpected: Throwable) {
            logger.warn(TAG, "DeepL translation failed: ${unexpected.javaClass.simpleName}")
            request.failed(TranslationError.Unexpected(unexpected.javaClass.simpleName))
        }
    }

    private suspend fun send(
        settings: RemoteProviderSettings,
        request: TranslationRequest,
        target: String,
    ): String {
        val payload = DeepLRequest(
            text = listOf(request.sourceText),
            targetLang = target,
            sourceLang = request.languages.source?.let(::sourceCodeFor),
            // Surrounding text the engine may read and must not translate.
            // Nothing populates `TranslationRequest.context` in the pipeline
            // yet — this exists so the experiment that decides whether anything
            // should can be run against the real service
            // (`BalloonContextExperimentTest`). DeepL does not bill for it.
            context = request.context?.takeIf { it.isNotBlank() },
        )

        val call = client.newCall(
            Request.Builder()
                .url(endpointOverride ?: hostFor(settings.apiKey.value))
                // DeepL's own scheme, not Bearer. The wrong one fails as 403
                // rather than as a clear 401, which is a confusing place to
                // start debugging.
                .addHeader("Authorization", "DeepL-Auth-Key ${settings.apiKey.value}")
                .post(
                    json.encodeToString(DeepLRequest.serializer(), payload)
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
        )

        // Enqueued rather than executed, so cancelling the coroutine cancels the
        // request — the coordinator drops work the moment a page moves on.
        val body = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            continuation.resumeWithException(HttpStatus(it.code))
                        } else {
                            continuation.resume(it.body?.string().orEmpty())
                        }
                    }
                }
            })
        }

        return json.decodeFromString(DeepLResponse.serializer(), body)
            .translations
            .firstOrNull()
            ?.text
            ?.trim()
            .orEmpty()
    }

    private fun TranslationRequest.completed(text: String, status: TranslationStatus) =
        TranslationResult(
            requestId = requestId,
            elementId = elementId,
            revision = revision,
            originalText = sourceText,
            translatedText = text,
            detectedSourceLanguage = languages.source,
            provider = this@DeepLTranslator.id,
            status = status,
        )

    private fun TranslationRequest.failed(error: TranslationError) = TranslationResult(
        requestId = requestId,
        elementId = elementId,
        revision = revision,
        originalText = sourceText,
        translatedText = "",
        provider = this@DeepLTranslator.id,
        status = TranslationStatus.Failed(error),
    )

    /**
     * Carries the HTTP status to the log, which the class name of a
     * plain [IOException] cannot. A general network failure keeps the
     * class-name treatment: its message can name a host or a URL the
     * user typed, and that is not something to print unasked.
     */
    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    @Serializable
    private data class DeepLRequest(
        val text: List<String>,
        @SerialName("target_lang") val targetLang: String,
        @SerialName("source_lang") val sourceLang: String? = null,
        val context: String? = null,
    )

    @Serializable
    private data class DeepLResponse(val translations: List<Translated> = emptyList()) {
        @Serializable
        data class Translated(val text: String = "")
    }

    companion object {
        private const val TAG = "DeepLTranslator"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            // An absent source_lang means "detect it", which is what the
            // language resolver expresses by leaving the source unset. Encoding
            // it explicitly would send a null that DeepL rejects.
            explicitNulls = false
            encodeDefaults = true
        }

        /**
         * Which DeepL to talk to, read off the key.
         *
         * Free-tier keys carry a `:fx` suffix and belong to a different host;
         * sending one to the paid host fails as 403. Deriving it is what lets a
         * user paste a key and nothing else — there is no address to get wrong,
         * and no way to pair a free key with the paid endpoint.
         */
        fun hostFor(apiKey: String): String =
            if (apiKey.trim().endsWith(FREE_KEY_SUFFIX)) FREE_ENDPOINT else PAID_ENDPOINT

        private const val FREE_KEY_SUFFIX = ":fx"
        private const val FREE_ENDPOINT = "https://api-free.deepl.com/v2/translate"
        private const val PAID_ENDPOINT = "https://api.deepl.com/v2/translate"

        /**
         * Targets DeepL accepts, by the primary subtag Babel carries.
         *
         * English and Portuguese have no unqualified target — DeepL wants the
         * variant — so a device set to plain `en` gets a decision made here
         * rather than a 400. Everything else is the subtag upper-cased, and this
         * map is what [supports] answers from.
         */
        private val TARGETS: Map<String, String> = mapOf(
            "bg" to "BG", "cs" to "CS", "da" to "DA", "de" to "DE", "el" to "EL",
            "en" to "EN-US", "es" to "ES", "et" to "ET", "fi" to "FI", "fr" to "FR",
            "hu" to "HU", "id" to "ID", "it" to "IT", "ja" to "JA", "ko" to "KO",
            "lt" to "LT", "lv" to "LV", "nb" to "NB", "no" to "NB", "nl" to "NL",
            "pl" to "PL", "pt" to "PT-PT", "ro" to "RO", "ru" to "RU", "sk" to "SK",
            "sl" to "SL", "sv" to "SV", "tr" to "TR", "uk" to "UK", "zh" to "ZH",
        )

        /** Sources take no variant: the same set, un-qualified. */
        private val SOURCES: Set<String> = TARGETS.keys

        /**
         * The primary subtag, because that is all DeepL takes.
         *
         * `LanguageResolver` already narrows a device tag to what a provider
         * accepts, and this is the last step of that for this one: `zh-Hans-CN`
         * becomes `ZH`, the script and region dropped because DeepL has nowhere
         * to put them.
         */
        private fun primary(tag: LanguageTag): String =
            tag.value.substringBefore('-').lowercase()

        fun targetCodeFor(tag: LanguageTag): String? = TARGETS[primary(tag)]

        fun sourceCodeFor(tag: LanguageTag): String? =
            primary(tag).takeIf { it in SOURCES }?.uppercase()
    }
}
