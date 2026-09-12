package com.babel.core.testing

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationError
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import com.babel.domain.translation.Translator
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Records every request it receives so tests can assert on de-duplication,
 * cancellation, and retry behaviour without a live provider.
 *
 * [delayMillis] is how the coordinator's stale-result protection gets exercised:
 * hold a translation open, push a newer revision, then let the old one finish.
 */
class FakeTranslator(
    override val id: ProviderId = ProviderId("fake"),
    private val transform: (String) -> String = { "<$it>" },
) : Translator {

    /** Every request seen, in arrival order. */
    val requests: MutableList<TranslationRequest> = CopyOnWriteArrayList()

    /** Requests whose coroutine was cancelled before producing a result. */
    val cancelled: MutableList<TranslationRequest> = CopyOnWriteArrayList()

    private val inFlight = AtomicInteger(0)

    /** Highest number of translations running at the same time. */
    @Volatile
    var peakConcurrency: Int = 0
        private set

    /** Artificial latency, so a request can be left in flight deliberately. */
    @Volatile
    var delayMillis: Long = 0

    /**
     * When set, the next [failTimes] calls fail with this error. Set
     * [failTimes] to [Int.MAX_VALUE] for a permanently broken provider.
     */
    @Volatile
    var failWith: TranslationError? = null

    @Volatile
    var failTimes: Int = Int.MAX_VALUE

    @Volatile
    var supportedPairs: (source: LanguageTag?, target: LanguageTag) -> Boolean = { _, _ -> true }

    private val failuresEmitted = AtomicInteger(0)

    val callCount: Int get() = requests.size

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean =
        supportedPairs(source, target)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        requests += request
        val running = inFlight.incrementAndGet()
        if (running > peakConcurrency) peakConcurrency = running
        try {
            if (delayMillis > 0) delay(delayMillis)

            val error = failWith
            if (error != null && failuresEmitted.get() < failTimes) {
                failuresEmitted.incrementAndGet()
                return request.failed(error)
            }

            return TranslationResult(
                requestId = request.requestId,
                elementId = request.elementId,
                revision = request.revision,
                originalText = request.sourceText,
                translatedText = transform(request.sourceText),
                detectedSourceLanguage = request.languages.source,
                provider = id,
                status = TranslationStatus.Translated,
            )
        } catch (cancellation: CancellationException) {
            cancelled += request
            throw cancellation
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun TranslationRequest.failed(error: TranslationError) = TranslationResult(
        requestId = requestId,
        elementId = elementId,
        revision = revision,
        originalText = sourceText,
        translatedText = "",
        provider = this@FakeTranslator.id,
        status = TranslationStatus.Failed(error),
    )

    fun reset() {
        requests.clear()
        cancelled.clear()
        failuresEmitted.set(0)
        failWith = null
        failTimes = Int.MAX_VALUE
        delayMillis = 0
        peakConcurrency = 0
    }
}
