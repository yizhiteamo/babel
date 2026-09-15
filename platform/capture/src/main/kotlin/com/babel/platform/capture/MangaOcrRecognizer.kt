package com.babel.platform.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
import com.babel.domain.vision.JapaneseScript
import com.babel.domain.vision.RecognizedLine
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Reads one speech balloon with a recogniser trained on comics.
 *
 * `kha-white/manga-ocr` via `ogkalu/manga-ocr-onnx` — a vision transformer
 * feeding a text decoder, Apache-2.0. Measured on the desktop it read a whole
 * transcribed page **exactly**, where the shipped ML Kit pipeline manages 87%
 * (`docs/milestones/v2.md`). It is the reason the detector came first: this
 * model expects a cropped balloon, not a screen.
 *
 * ## What it returns
 *
 * One string for the whole crop, with no line boxes — it reads a balloon the way
 * a person does, not the way a text detector does. So the single
 * [RecognizedLine] it produces covers the crop and carries **no** orientation:
 * the model has no opinion, and `TextOrientationDetector` infers one from the
 * shape, which is the path that already exists for engines that stay silent.
 *
 * ## The cost, stated up front
 *
 * This export carries **no key/value cache**. Every character generated re-runs
 * the decoder over the whole sequence so far, so cost grows with the square of
 * the length rather than linearly. Stage 2 measured 167–694ms per region on an
 * emulator. That is the price of the accuracy, and it is why this is per
 * balloon rather than per page.
 */
@Singleton
internal class MangaOcrRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: BabelLogger,
) : TextRecognizer {

    private val modelsDir: File
        get() = File(context.getExternalFilesDir(null), MODELS_DIR)

    /** Every piece has to be present; a half-downloaded model is unavailable. */
    val isAvailable: Boolean
        get() = !failed && FILES.all { File(modelsDir, it).exists() }

    @Volatile
    private var failed = false

    private val loading = Mutex()
    private var loaded: Sessions? = null

    private class Sessions(
        val encoder: OrtSession,
        val decoder: OrtSession,
        val vocabulary: List<String>,
    )

    /**
     * Japanese, but only when what came back actually says so in kana.
     *
     * The same rule the other recogniser uses, and for the same reason: the
     * model is built for Japanese, but what it produced on any given crop is a
     * separate question ([JapaneseScript]).
     */
    override fun languageOf(text: String): LanguageTag? =
        JAPANESE.takeIf { JapaneseScript.isPresentIn(text) }

    override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
        val sessions = sessions() ?: return emptyList()

        return try {
            val text = withContext(dispatchers.default) { read(sessions, frame) }
            if (text.isBlank()) {
                emptyList()
            } else {
                listOf(
                    RecognizedLine(
                        text = text,
                        bounds = TextBounds(0, 0, frame.width, frame.height, CoordinateSpace.SCREEN),
                        // No opinion: see the class comment.
                        orientation = null,
                    ),
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // One unreadable balloon is one missing translation, not the end of
            // manga mode.
            logger.warn(TAG, "recognition failed: ${failure.javaClass.simpleName}")
            emptyList()
        }
    }

    /**
     * Closes both sessions, which is where the memory is.
     *
     * Measured with them loaded: 434MB native, **589MB** PSS. Holding that while
     * image translation is switched off buys nothing, and the reload costs
     * ~900ms — a price worth paying once per session rather than carrying for
     * the life of the process.
     */
    suspend fun release() {
        loading.withLock {
            loaded?.let {
                it.encoder.close()
                it.decoder.close()
            }
            loaded = null
            failed = false
        }
    }

    private suspend fun sessions(): Sessions? {
        loaded?.let { return it }
        if (failed || !isAvailable) return null

        return loading.withLock {
            loaded ?: try {
                val started = System.currentTimeMillis()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                val created = Sessions(
                    encoder = environment.createSession(
                        File(modelsDir, ENCODER).absolutePath, options,
                    ),
                    decoder = environment.createSession(
                        File(modelsDir, DECODER).absolutePath, options,
                    ),
                    vocabulary = File(modelsDir, VOCAB).readLines(),
                )
                logger.info(
                    TAG,
                    "manga-ocr loaded in ${System.currentTimeMillis() - started}ms, " +
                        "vocabulary ${created.vocabulary.size}",
                )
                created.also { loaded = it }
            } catch (failure: Throwable) {
                // Latched: a truncated file is reported once, not retried per
                // balloon for the rest of the session.
                failed = true
                logger.warn(TAG, "manga-ocr could not be loaded: ${failure.javaClass.simpleName}")
                null
            }
        }
    }

    private suspend fun read(sessions: Sessions, crop: Bitmap): String {
        val environment = OrtEnvironment.getEnvironment()
        val pixels = crop.asPixelValues(environment)

        val encoded = try {
            sessions.encoder.run(mapOf("pixel_values" to pixels))
        } finally {
            pixels.close()
        }

        return try {
            decode(environment, sessions, encoded[0] as OnnxTensor)
        } finally {
            encoded.close()
        }
    }

    /**
     * Greedy, feeding the whole sequence back each step.
     *
     * Not a choice — this export has no key/value cache, so there is nothing to
     * feed forward. A `for` with `break` rather than `repeat` with a
     * `return@repeat`: the latter skips one step instead of stopping, which runs
     * the loop past the end token and reports a latency that is fiction.
     */
    private suspend fun decode(
        environment: OrtEnvironment,
        sessions: Sessions,
        hidden: OnnxTensor,
    ): String {
        val tokens = mutableListOf(CLS)

        for (step in 0 until MAX_TOKENS) {
            // A balloon the user has already scrolled past is not worth
            // finishing, and this loop is where the time goes.
            coroutineContext.ensureActive()

            val ids = OnnxTensor.createTensor(
                environment,
                LongBuffer.wrap(tokens.map(Int::toLong).toLongArray()),
                longArrayOf(1, tokens.size.toLong()),
            )
            val next = try {
                sessions.decoder.run(
                    mapOf("input_ids" to ids, "encoder_hidden_states" to hidden),
                ).use { output ->
                    @Suppress("UNCHECKED_CAST")
                    val logits = (output[0].value as Array<Array<FloatArray>>)[0].last()
                    var best = 0
                    for (index in logits.indices) if (logits[index] > logits[best]) best = index
                    best
                }
            } finally {
                ids.close()
            }

            if (next == SEP) break
            tokens += next
        }

        return tokens.drop(1)
            .mapNotNull { sessions.vocabulary.getOrNull(it) }
            // `[CLS]`, `[SEP]`, `[UNK]` and friends are structure, not text.
            .filterNot { it.startsWith("[") }
            .joinToString("")
            .trim()
    }

    /**
     * Greyscale repeated across three channels and scaled to [-1, 1], which is
     * what this model was trained on. Feeding it colour produces plausible
     * nonsense rather than an error.
     */
    private fun Bitmap.asPixelValues(environment: OrtEnvironment): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(this, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled !== this) scaled.recycle()

        val buffer = FloatBuffer.allocate(3 * INPUT * INPUT)
        for (channel in 0 until 3) {
            for (pixel in pixels) {
                val grey = (
                    Color.red(pixel) * 299 +
                        Color.green(pixel) * 587 +
                        Color.blue(pixel) * 114
                    ) / 1000
                buffer.put((grey / 255f - 0.5f) / 0.5f)
            }
        }
        buffer.rewind()

        return OnnxTensor.createTensor(
            environment,
            buffer,
            longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()),
        )
    }

    private companion object {
        const val TAG = "MangaOcr"
        const val MODELS_DIR = "models"
        const val ENCODER = "encoder_model_int8.onnx"
        const val DECODER = "decoder_model_int8.onnx"
        const val VOCAB = "vocab.txt"
        val FILES = listOf(ENCODER, DECODER, VOCAB)

        val JAPANESE = LanguageTag("ja")

        const val INPUT = 224
        const val CLS = 2
        const val SEP = 3

        /** Longer than any balloon this has met; a runaway decode stops here. */
        const val MAX_TOKENS = 96
    }
}
