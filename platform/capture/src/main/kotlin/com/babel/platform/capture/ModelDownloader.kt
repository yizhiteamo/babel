package com.babel.platform.capture

import android.content.Context
import android.net.ConnectivityManager
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.domain.vision.RecognizerModel
import com.babel.domain.vision.RecognizerModelState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the recogniser that is too large to ship (ADR 011).
 *
 * The detector travels with the app; manga-ocr is 117MB and only a reader who
 * actually opens a comic should pay for it. Everything here exists because a
 * download can go wrong in ways a bundled file cannot.
 *
 * ## What it guards against
 *
 * - **A connection that dies at 90%.** Each file resumes from what is already
 *   on disk, via a range request. 117MB over a phone connection will be
 *   interrupted; starting again from zero would make it nearly unfinishable.
 * - **A file that arrives wrong.** Every download is hashed before it counts.
 *   `MangaOcrRecognizer` latches `failed` on a bad model and stops trying for
 *   the rest of the session — sensible when a human pushed the wrong file,
 *   useless as the only defence against a truncated download.
 * - **A half-written file looking finished.** Bytes land in `.part` and are
 *   renamed only after the hash matches, so an interrupted run never leaves
 *   something [MangaOcrRecognizer.isAvailable] would accept.
 *
 * ## The hashes are pinned
 *
 * To the exact files this was measured against. If the upstream repository
 * publishes different weights, the download fails verification rather than
 * silently installing a model nobody tested — the safe direction to fail in,
 * and a signal that the app needs updating rather than the user's device.
 */
@Singleton
internal class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: BabelLogger,
) : RecognizerModel {

    /**
     * Built here rather than injected: which HTTP client fetches a model is an
     * implementation detail, and a binding for a bare `OkHttpClient` would be
     * a broad one for the whole app to trip over later.
     *
     * Timeouts are generous where the chat translator's are short, and for the
     * opposite reason: nobody is waiting on this frame. Read timeout applies
     * per socket read, not to the whole 117MB.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val _state = MutableStateFlow<RecognizerModelState>(
        if (isInstalled()) {
            RecognizerModelState.Installed
        } else {
            RecognizerModelState.Absent
        },
    )
    override val state: StateFlow<RecognizerModelState> = _state.asStateFlow()

    override val totalBytes: Long = TOTAL_BYTES

    private val running = Mutex()

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), MODELS_DIR)

    /**
     * The same question [MangaOcrRecognizer] asks, deliberately: whether the
     * recogniser will find what it needs. Existence only — the hash was checked
     * when the file was installed, and re-reading 117MB to answer a UI question
     * would cost more than it is worth.
     */
    fun isInstalled(): Boolean = MODELS.all { File(modelsDir, it.name).exists() }

    /** Total bytes still to fetch, for a UI that wants to say so before asking. */
    fun remainingBytes(): Long = MODELS
        .filterNot { File(modelsDir, it.name).exists() }
        .sumOf { it.bytes - partOf(it).length().coerceAtMost(it.bytes) }

    /**
     * Whether this would be paid for by the megabyte.
     *
     * Not enforced here — refusing outright would be wrong for someone on an
     * unlimited plan the system reports as metered. The caller asks the user.
     */
    override fun isMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

    /**
     * Re-reads the disk, without disturbing anything in flight.
     *
     * A running download owns the state — overwriting its progress with a
     * snapshot would make the bar jump backwards — and a [RecognizerModelState.Failed]
     * survives a refresh that finds nothing new, because the reason it failed
     * is still the last thing that happened and is worth more to the reader
     * than a bare "absent".
     */
    override fun refresh() {
        if (running.isLocked) return
        _state.value = when {
            isInstalled() -> RecognizerModelState.Installed
            _state.value is RecognizerModelState.Failed -> _state.value
            else -> RecognizerModelState.Absent
        }
    }

    /**
     * Fetches whatever is missing. Safe to call when everything is present.
     *
     * Cancelling leaves the `.part` files in place, which is the point: the
     * next call continues from there.
     */
    override suspend fun install() {
        if (running.isLocked) return

        running.withLock {
            if (isInstalled()) {
                _state.value = RecognizerModelState.Installed
                return
            }

            try {
                withContext(dispatchers.io) {
                    modelsDir.mkdirs()
                    val total = MODELS.sumOf { it.bytes }
                    var done = MODELS.sumOf { model ->
                        val file = File(modelsDir, model.name)
                        if (file.exists()) model.bytes else 0L
                    }

                    for (model in MODELS) {
                        if (File(modelsDir, model.name).exists()) continue
                        fetch(model, alreadyDone = done, total = total)
                        done += model.bytes
                    }
                }
                _state.value = RecognizerModelState.Installed
                logger.info(TAG, "manga-ocr installed")
            } catch (cancellation: CancellationException) {
                // The partial files stay. Report where it stopped rather than
                // Absent, so the UI can offer to continue rather than restart.
                _state.value = RecognizerModelState.Absent
                throw cancellation
            } catch (failure: Throwable) {
                logger.warn(TAG, "model download failed: ${failure.javaClass.simpleName}")
                _state.value = RecognizerModelState.Failed(failure.cause())
            }
        }
    }

    private suspend fun fetch(model: Model, alreadyDone: Long, total: Long) {
        val part = partOf(model)
        val have = part.length()

        val request = Request.Builder()
            .url(model.url)
            // Resume. A server that ignores this answers 200 and the whole file
            // arrives, which the truncation check below turns into a restart
            // rather than a corrupt append.
            .apply { if (have > 0) addHeader("Range", "bytes=$have-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")

            val resuming = response.code == HTTP_PARTIAL && have > 0
            if (have > 0 && !resuming) {
                logger.debug(TAG, "server ignored the range request; starting ${model.name} over")
                part.delete()
            }

            val from = if (resuming) have else 0L
            var written = from

            response.body?.byteStream()?.use { source ->
                java.io.FileOutputStream(part, resuming).use { sink ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        sink.write(buffer, 0, read)
                        written += read
                        _state.value = RecognizerModelState.Running(
                            bytes = alreadyDone + written,
                            total = total,
                        )
                    }
                }
            } ?: throw IOException("empty body")
        }

        val actual = part.sha256()
        if (actual != model.sha256) {
            // Deleted rather than kept: resuming onto bytes that already failed
            // verification would fail again forever.
            part.delete()
            throw IOException("checksum mismatch for ${model.name}")
        }

        // Renamed last, so nothing before this moment looks like a usable model.
        if (!part.renameTo(File(modelsDir, model.name))) {
            throw IOException("could not install ${model.name}")
        }
        logger.info(TAG, "${model.name} verified and installed")
    }

    private fun partOf(model: Model) = File(modelsDir, "${model.name}$PART_SUFFIX")

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { stream ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Our cause, never the server's: an error body is not ours to show. */
    private fun Throwable.cause(): RecognizerModelState.Failed.Cause = when {
        message?.startsWith("checksum") == true -> RecognizerModelState.Failed.Cause.CORRUPT
        this is IOException -> RecognizerModelState.Failed.Cause.NETWORK
        else -> RecognizerModelState.Failed.Cause.UNEXPECTED
    }

    private data class Model(
        val name: String,
        val url: String,
        val bytes: Long,
        val sha256: String,
    )

    companion object {
        private const val TAG = "ModelDownloader"
        private const val MODELS_DIR = "models"
        private const val PART_SUFFIX = ".part"
        private const val BUFFER = 64 * 1024
        private const val HTTP_PARTIAL = 206

        private const val HOST = "https://huggingface.co/ogkalu/manga-ocr-onnx/resolve/main"

        /**
         * Sizes and hashes are those the host actually serves, confirmed
         * against `X-Linked-ETag` — which for a large-file-storage object *is*
         * its SHA-256, so the two big models were verified without downloading
         * 117MB to check. Apache-2.0; attribution ships in `assets/licenses`.
         */
        private val MODELS = listOf(
            Model(
                name = "encoder_model_int8.onnx",
                url = "$HOST/encoder_model_int8.onnx",
                bytes = 87_074_595L,
                sha256 = "0eaf2b867292a44700ce38ef028b90639a2e36fc4c18c2bcdd1de7409488adb3",
            ),
            Model(
                name = "decoder_model_int8.onnx",
                url = "$HOST/decoder_model_int8.onnx",
                bytes = 29_675_736L,
                sha256 = "3ff0d4c34c4a66613d98ff93e0b17f22a7b09dfbeec195c3e4d6f595af3a6b6c",
            ),
            // Taken from what the server sends, not from a working copy. This
            // one is small enough to live in git rather than LFS, so it arrives
            // with CRLF line endings while a copy fetched onto a Windows
            // machine had been converted to LF — same 6144 tokens, different
            // bytes, and a hash taken from the wrong one fails every download.
            Model(
                name = "vocab.txt",
                url = "$HOST/vocab.txt",
                bytes = 30_216L,
                sha256 = "5cb5c5586d98a2f331d9f8828e4586479b0611bfba5d8c3b6dadffc84d6a36a3",
            ),
        )

        /** What a UI shows before anyone has agreed to spend it. */
        val TOTAL_BYTES: Long = MODELS.sumOf { it.bytes }
    }
}
