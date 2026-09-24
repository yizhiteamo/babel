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
import java.util.Locale
import java.util.concurrent.TimeUnit
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
 * Translates through an OpenAI-compatible chat endpoint.
 *
 * On-device translation was measured against a hosted model on the same fifteen
 * balloons and tops out around half the distance, at 579MB
 * (`docs/milestones/v2.md`). Closing the rest means sending the text somewhere,
 * which is a decision the user makes rather than one this code assumes — see
 * ADR 010 and `docs/systems/privacy.md`.
 *
 * ## Why a chat endpoint rather than a named service
 *
 * One request shape covers the hosted model this was asked for, the several
 * providers that copy its API, and anything the user runs themselves. Naming a
 * vendor would buy nothing and exclude all of that.
 *
 * Text reaching this class has already passed the privacy policy — that is what
 * the coordinator checks before any provider is called, and it is why providers
 * are only reachable through the translation layer (`docs/systems/privacy.md`).
 */
class ChatTranslator(
    private val settingsRepository: SettingsRepository,
    private val logger: BabelLogger = BabelLogger.NoOp,
    private val client: OkHttpClient = defaultClient(),
) : Translator {

    override val id: ProviderId = RemoteService.CHAT.providerId

    /**
     * A general model translates between pairs nobody configured it for, so
     * there is no list to check against. Being unconfigured is the one real
     * reason to decline, and [translate] reports that as a failure carrying a
     * cause rather than silently doing nothing.
     */
    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean = true

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val settings = settingsRepository.settings.first().remote
        if (!settings.isConfigured) {
            return request.failed(
                TranslationError.ProviderRejected(id, "remote translation is not configured"),
            )
        }

        return try {
            val started = System.currentTimeMillis()
            val translated = send(settings, request)
            // Same reason as the other remote route: the leg that changes with
            // the engine is the one worth timing. Timing only — never the text
            // and never the body.
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
            logger.warn(TAG, "remote translation rejected: HTTP ${rejected.code}")
            request.failed(TranslationError.Network("HTTP ${rejected.code}"))
        } catch (failure: IOException) {
            // Logged without the text and without the response body: what went
            // over the wire stays out of diagnostics exactly as screen content
            // does, and an error body can echo the request back.
            logger.warn(TAG, "remote translation failed: ${failure.javaClass.simpleName}")
            request.failed(TranslationError.Network(failure.javaClass.simpleName))
        } catch (unexpected: Throwable) {
            logger.warn(TAG, "remote translation failed: ${unexpected.javaClass.simpleName}")
            request.failed(TranslationError.Unexpected(unexpected.javaClass.simpleName))
        }
    }

    private suspend fun send(
        settings: RemoteProviderSettings,
        request: TranslationRequest,
    ): String {
        val payload = ChatRequest(
            model = settings.model,
            messages = listOf(
                ChatMessage("system", instruction(request)),
                ChatMessage("user", request.sourceText),
            ),
        )

        val call = client.newCall(
            Request.Builder()
                .url(settings.endpoint)
                .addHeader("Authorization", "Bearer ${settings.apiKey.value}")
                .post(
                    json.encodeToString(ChatRequest.serializer(), payload)
                        .toRequestBody(JSON_MEDIA_TYPE),
                )
                .build(),
        )

        // Enqueued rather than executed, so cancelling the coroutine cancels
        // the request. The `Translator` contract requires that — the
        // coordinator cancels work the moment a page moves on, and a blocking
        // call would hold both a thread and a connection past that point.
        val body = suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!it.isSuccessful) {
                            // Status only. The body of a rejection routinely
                            // quotes the request back, and sometimes the key.
                            continuation.resumeWithException(HttpStatus(it.code))
                        } else {
                            continuation.resume(it.body?.string().orEmpty())
                        }
                    }
                }
            })
        }

        return json.decodeFromString(ChatResponse.serializer(), body)
            .choices
            .firstOrNull()
            ?.message
            ?.content
            ?.trim()
            .orEmpty()
    }

    /**
     * Says what the text is, not just what to do with it.
     *
     * Telling the model it is receiving one speech balloon is what stops it
     * answering the line instead of translating it — the failure that ruled out
     * a local instruction-following model was exactly this, on short input.
     */
    /**
     * What the model is told, and why each sentence is there.
     *
     * The first two are the job. The third is the one that stops a chat model
     * from answering the *reader*: asked to translate the browser's address
     * bar, it replied "I can't access files on your device. Please paste the
     * text…", and that sentence was drawn over the address bar. Told to echo
     * instead, an untranslatable input comes back unchanged — which
     * [translate] already reports as `Unchanged`, and an unchanged element is
     * never rendered. A refusal becomes a no-op rather than a caption.
     *
     * Measured rather than assumed: a plain `https://…` and a `host:port`
     * already came back verbatim, so the model does this by itself for inputs
     * it recognises as addresses. The instruction extends that to the ones it
     * would otherwise want to discuss (`docs/milestones/v2.md`).
     *
     * The fourth is a separate defect: `apparently` was left sitting in the
     * middle of a Chinese sentence. Nothing had ever asked for the whole output
     * to be in the target language.
     */
    private fun instruction(request: TranslationRequest): String = buildString {
        append("Translate the comic speech balloon the user sends")
        request.languages.source?.let { append(" from ${it.value}") }
        append(" into ${nameOf(request.languages.target)}.")
        append(" Reply with the translation only: no explanation, no romanisation,")
        append(" no quotation marks that the original did not have.")
        append(" If the text cannot be translated — an address, a file path, code,")
        append(" or nonsense — reply with it exactly as given and nothing else;")
        append(" never address the user and never explain what you cannot do.")
        append(" Write the whole reply in ${nameOf(request.languages.target)}:")
        append(" leave no word of the source language standing in it.")
    }

    /**
     * A language written the way a person would name it.
     *
     * The instruction is prose and a BCP-47 code is not, and the difference is
     * not cosmetic: asked for `zh` this model left `apparently` sitting in the
     * middle of its Chinese, and asked for `zh-Hans-CN` it translated the same
     * sentence correctly. `zh` is ambiguous — Chinese, script and region
     * unsaid — and the user reaches it by picking Chinese by hand, while
     * following the system gives the fuller tag. So the defect appeared only
     * for people who chose their language deliberately (`docs/milestones/v2.md`).
     *
     * Falls back to the tag itself for anything `Locale` cannot name, which is
     * no worse than what this did before.
     */
    private fun nameOf(tag: LanguageTag): String {
        val locale = Locale.forLanguageTag(tag.value)
        val name = locale.getDisplayName(Locale.ENGLISH)
        return name.ifBlank { tag.value }
    }

    private fun TranslationRequest.completed(text: String, status: TranslationStatus) =
        TranslationResult(
            requestId = requestId,
            elementId = elementId,
            revision = revision,
            originalText = sourceText,
            translatedText = text,
            detectedSourceLanguage = languages.source,
            provider = this@ChatTranslator.id,
            status = status,
        )

    private fun TranslationRequest.failed(error: TranslationError) = TranslationResult(
        requestId = requestId,
        elementId = elementId,
        revision = revision,
        originalText = sourceText,
        translatedText = "",
        provider = this@ChatTranslator.id,
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
    private data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        // Dialogue should come back the same way twice: a page re-scanned must
        // not produce a different reading of the same balloon.
        val temperature: Double = 0.0,
    )

    @Serializable
    private data class ChatMessage(val role: String, val content: String)

    @Serializable
    private data class ChatResponse(val choices: List<Choice> = emptyList()) {
        @Serializable
        data class Choice(@SerialName("message") val message: ChatMessage? = null)
    }

    companion object {
        private const val TAG = "ChatTranslator"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * Timeouts are short on purpose. A translation nobody is still looking
         * at is worth nothing, and the coordinator cancels superseded work
         * anyway — a long read timeout would only hold a connection open past
         * the point where the page has moved on.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
