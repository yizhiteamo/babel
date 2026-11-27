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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Records every request it receives so tests can assert on de-duplication,
 * cancellation, and retry behaviour without a live provider.
 *
 * [delayMillis] steps through virtual time; [holdRequests] holds each call open
 * indefinitely until [release] / [releaseAll] or coroutine cancellation, so a
 * test can tell genuine cancellation apart from a request that finished and was
 * later dropped by a staleness check.
 */
class FakeTranslator(
    override val id: ProviderId = ProviderId("fake"),
    private val transform: (String) -> String = { "<$it>" },
) : Translator {

    /** Every request seen, in arrival order. */
    val requests: MutableList<TranslationRequest> = CopyOnWriteArrayList()

    /** Requests that returned a [TranslationResult] without being cancelled. */
    val completed: MutableList<TranslationRequest> = CopyOnWriteArrayList()

    /** Requests whose coroutine was cancelled before producing a result. */
    val cancelled: MutableList<TranslationRequest> = CopyOnWriteArrayList()

    private val heldLock = Any()
    private val held = LinkedHashMap<TranslationRequest, CompletableDeferred<Unit>>()
    private val runningRequests = LinkedHashSet<TranslationRequest>()

    /** Requests currently suspended inside [translate]. */
    val active: List<TranslationRequest>
        get() = synchronized(heldLock) { runningRequests.toList() }

    private val inFlight = AtomicInteger(0)

    /** Highest number of translations running at the same time. */
    @Volatile
    var peakConcurrency: Int = 0
        private set

    /** Artificial latency, so a request can be left in flight deliberately. */
    @Volatile
    var delayMillis: Long = 0

    /**
     * When true, arriving requests suspend until [release] / [releaseAll] lets
     * them finish, or until their coroutine is cancelled.
     */
    @Volatile
    var holdRequests: Boolean = false

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
        val gate = synchronized(heldLock) {
            runningRequests += request
            if (holdRequests) CompletableDeferred<Unit>().also { held[request] = it } else null
        }
        val running = inFlight.incrementAndGet()
        if (running > peakConcurrency) peakConcurrency = running
        try {
            gate?.await()
            if (delayMillis > 0) delay(delayMillis)

            val error = failWith
            val result = if (error != null && failuresEmitted.get() < failTimes) {
                failuresEmitted.incrementAndGet()
                request.failed(error)
            } else {
                TranslationResult(
                    requestId = request.requestId,
                    elementId = request.elementId,
                    revision = request.revision,
                    originalText = request.sourceText,
                    translatedText = transform(request.sourceText),
                    detectedSourceLanguage = request.languages.source,
                    provider = id,
                    status = TranslationStatus.Translated,
                )
            }
            completed += request
            return result
        } catch (cancellation: CancellationException) {
            cancelled += request
            throw cancellation
        } finally {
            inFlight.decrementAndGet()
            synchronized(heldLock) {
                runningRequests -= request
                held.remove(request)
            }
        }
    }

    /** Lets every currently held request matching [predicate] finish. */
    fun release(predicate: (TranslationRequest) -> Boolean): Int {
        val gates = synchronized(heldLock) {
            held.filterKeys(predicate).values.toList()
        }
        gates.forEach { it.complete(Unit) }
        return gates.size
    }

    /** Lets every currently held request for [sourceText] finish. */
    fun release(sourceText: String): Int = release { it.sourceText == sourceText }

    /** Lets every currently held request finish. */
    fun releaseAll(): Int = release { true }

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
        releaseAll()
        requests.clear()
        completed.clear()
        cancelled.clear()
        failuresEmitted.set(0)
        failWith = null
        failTimes = Int.MAX_VALUE
        delayMillis = 0
        holdRequests = false
        peakConcurrency = 0
    }
}
