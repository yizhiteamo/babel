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
import com.babel.core.model.TextBounds
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A comic balloon detector, run through ONNX Runtime.
 *
 * The model is `ogkalu/comic-text-and-bubble-detector` — RT-DETR-v2, Apache-2.0,
 * 10.6MB quantised. Measured on the desktop across six pages it found every
 * balloon and correctly reported **none** on the character-sheet page that has
 * no dialogue, and end to end with a comic recogniser it read one transcribed
 * page exactly where the shipped pipeline manages 87% (`docs/milestones/v2.md`).
 *
 * ## Where the model comes from
 *
 * Read from the app's own files directory, and absent is a normal state: weights
 * are somebody else's artefact and are not committed (`.gitignore`), so this
 * reports [isAvailable] false and the caller keeps its existing path. Deciding
 * how the file gets onto a user's device — bundled or fetched — is a separate
 * question, and one worth answering after the numbers rather than before.
 *
 * ```
 * adb push models/detector.onnx /sdcard/Android/data/com.babel/files/models/
 * ```
 *
 * ## The trap
 *
 * `orig_target_sizes` is **(width, height)**. The other order puts every box
 * past the right edge of the page and makes a working detector score 2/5 — an
 * hour was lost to it once already.
 */
@Singleton
internal class OnnxBubbleDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: BabelLogger,
) : TextDetector {

    private val modelFile: File
        get() = File(File(context.getExternalFilesDir(null), MODELS_DIR), MODEL_NAME)

    override val isAvailable: Boolean get() = failed.not() && modelFile.exists()

    @Volatile
    private var failed = false

    private val loading = Mutex()
    private var session: OrtSession? = null

    override suspend fun detect(frame: Bitmap): List<DetectedBubble> {
        val active = session() ?: return emptyList()

        return try {
            withContext(dispatchers.default) { run(active, frame) }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // One bad page must not take the session down; a failure here means
            // the caller reads nothing this scan, not that manga mode ends.
            logger.warn(TAG, "detection failed: ${failure.javaClass.simpleName}")
            emptyList()
        }
    }

    override suspend fun release() {
        loading.withLock {
            session?.close()
            session = null
            // Not latched: releasing is a deliberate hand-back, not a failure,
            // and the next scan is expected to load it again.
            failed = false
        }
    }

    private suspend fun session(): OrtSession? {
        session?.let { return it }
        if (failed || !modelFile.exists()) return null

        return loading.withLock {
            session ?: try {
                val started = System.currentTimeMillis()
                val created = OrtEnvironment.getEnvironment()
                    .createSession(modelFile.absolutePath, OrtSession.SessionOptions())
                logger.info(TAG, "detector loaded in ${System.currentTimeMillis() - started}ms")
                created.also { session = it }
            } catch (failure: Throwable) {
                // Latched, so a broken or truncated file is reported once rather
                // than retried on every frame for the rest of the session.
                failed = true
                logger.warn(TAG, "detector could not be loaded: ${failure.javaClass.simpleName}")
                null
            }
        }
    }

    private fun run(session: OrtSession, frame: Bitmap): List<DetectedBubble> {
        val environment = OrtEnvironment.getEnvironment()
        val image = frame.asInputTensor(environment)
        // (width, height). See the class comment.
        val sizes = OnnxTensor.createTensor(
            environment,
            LongBuffer.wrap(longArrayOf(frame.width.toLong(), frame.height.toLong())),
            longArrayOf(1, 2),
        )

        try {
            session.run(mapOf("images" to image, "orig_target_sizes" to sizes)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val labels = (result[0].value as Array<LongArray>)[0]

                @Suppress("UNCHECKED_CAST")
                val boxes = (result[1].value as Array<Array<FloatArray>>)[0]

                @Suppress("UNCHECKED_CAST")
                val scores = (result[2].value as Array<FloatArray>)[0]

                return pair(labels, boxes, scores, frame)
            }
        } finally {
            image.close()
            sizes.close()
        }
    }

    /**
     * Matches each piece of lettering to the balloon it sits in.
     *
     * By containment rather than by overlap-over-union: a balloon is much larger
     * than its text, so IoU scores a correct pairing low. The question worth
     * asking is whether the text is *inside* the balloon.
     */
    private fun pair(
        labels: LongArray,
        boxes: Array<FloatArray>,
        scores: FloatArray,
        frame: Bitmap,
    ): List<DetectedBubble> {
        val texts = mutableListOf<TextBounds>()
        val balloons = mutableListOf<TextBounds>()

        for (index in labels.indices) {
            if (scores[index] < SCORE_FLOOR) continue
            val box = boxes[index].toBounds(frame) ?: continue
            when (labels[index].toInt()) {
                LABEL_TEXT_IN_BUBBLE -> texts += box
                LABEL_BALLOON -> balloons += box
            }
        }

        return texts.map { text ->
            DetectedBubble(
                text = text,
                balloon = balloons.firstOrNull { text.mostlyInside(it) },
            )
        }
    }

    private fun FloatArray.toBounds(frame: Bitmap): TextBounds? {
        val bounds = TextBounds(
            left = this[0].toInt().coerceIn(0, frame.width),
            top = this[1].toInt().coerceIn(0, frame.height),
            right = this[2].toInt().coerceIn(0, frame.width),
            bottom = this[3].toInt().coerceIn(0, frame.height),
            space = CoordinateSpace.SCREEN,
        )
        return bounds.takeIf { it.width >= MIN_SIDE && it.height >= MIN_SIDE }
    }

    private fun TextBounds.mostlyInside(outer: TextBounds): Boolean {
        val overlapWidth = (minOf(right, outer.right) - maxOf(left, outer.left)).coerceAtLeast(0)
        val overlapHeight = (minOf(bottom, outer.bottom) - maxOf(top, outer.top)).coerceAtLeast(0)
        val area = width.toLong() * height
        if (area <= 0) return false
        return overlapWidth.toLong() * overlapHeight >= area * CONTAINED_PERCENT / 100
    }

    private fun Bitmap.asInputTensor(environment: OrtEnvironment): OnnxTensor {
        val scaled = Bitmap.createScaledBitmap(this, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        if (scaled !== this) scaled.recycle()

        // Channel-first, which is what the export expects; interleaved input
        // produces boxes that look plausible and are wrong.
        val buffer = FloatBuffer.allocate(3 * INPUT * INPUT)
        for (channel in 0 until 3) {
            for (pixel in pixels) {
                val value = when (channel) {
                    0 -> Color.red(pixel)
                    1 -> Color.green(pixel)
                    else -> Color.blue(pixel)
                }
                buffer.put(value / 255f)
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
        const val TAG = "BubbleDetector"
        const val MODELS_DIR = "models"
        const val MODEL_NAME = "detector.onnx"

        const val INPUT = 640
        const val SCORE_FLOOR = 0.5f

        const val LABEL_BALLOON = 0
        const val LABEL_TEXT_IN_BUBBLE = 1

        /** Below this a box is a misdetection, not lettering. */
        const val MIN_SIDE = 8

        /** How much of the lettering must sit in a balloon to call it its own. */
        const val CONTAINED_PERCENT = 80
    }
}
