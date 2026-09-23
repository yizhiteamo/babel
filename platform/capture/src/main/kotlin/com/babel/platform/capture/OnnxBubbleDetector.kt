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
 * **Bundled.** At 11MB against a per-ABI package of roughly seventy, it is the
 * cheap half of what manga mode needs — correct balloon grouping and no
 * interface noise — and it buys that with nothing asked of the user. The
 * expensive half, manga-ocr at 117MB, is fetched on demand instead (ADR 011).
 *
 * Redistributing it is what obliges Babel to carry the licence and attribution
 * in `assets/licenses/`; running it from a pushed file never did.
 *
 * A file in the app's own files directory still **wins** over the bundled one,
 * so a different export can be tried on a device without a rebuild:
 *
 * ```
 * adb push models/detector.onnx /sdcard/Android/data/com.babel/files/models/
 * ```
 *
 * [isAvailable] therefore answers true on any normal install — but the false
 * branch stays, because reading an asset can still fail and `DetectingPageReader`
 * has a tested path for that.
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

    /** A pushed override, when one is present. See the class comment. */
    private val pushedFile: File
        get() = File(File(context.getExternalFilesDir(null), MODELS_DIR), MODEL_NAME)

    override val isAvailable: Boolean get() = failed.not()

    @Volatile
    private var failed = false

    private val loading = Mutex()
    private var session: OrtSession? = null

    override suspend fun detect(frame: Bitmap): List<DetectedBubble> =
        pair(detectRaw(frame, SCORE_FLOOR))

    /**
     * Every box the model reported, with its label and score, before any of the
     * decisions [detect] makes on top of them.
     *
     * Exists so those decisions can be *measured* rather than guessed at.
     * `SCORE_FLOOR` and the choice to ignore free text were both set from a
     * six-page desktop run, and a later eight-page run on the device found a
     * dark page where the detector boxes rain and panel edges as balloons
     * (`docs/milestones/v2.md`). Choosing between a higher floor and demanding
     * an enclosing balloon needs the raw scores of the boxes each would drop.
     *
     * A harness could not reimplement this: the input tensor is channel-first,
     * `orig_target_sizes` is (width, height), and getting either wrong produces
     * plausible boxes that are wrong. A second copy of that would measure
     * itself. Mirroring a private `Int`, as `FrameSignatureMeasurementTest`
     * does, is a different and much smaller thing.
     *
     * Not part of [TextDetector]: the labels are this model's, and nothing in
     * the pipeline should learn them.
     */
    internal suspend fun detectRaw(frame: Bitmap, floor: Float): List<RawDetection> {
        val active = session() ?: return emptyList()

        return try {
            withContext(dispatchers.default) { run(active, frame, floor) }
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
        if (failed) return null

        return loading.withLock {
            session ?: try {
                val started = System.currentTimeMillis()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions()
                // A pushed file wins, so an experiment does not need a rebuild.
                // Otherwise the bundled copy, read into memory rather than
                // copied to disk first: 11MB once at load beats carrying a
                // second copy of the same bytes on the device forever.
                val pushed = pushedFile
                val created = if (pushed.exists()) {
                    environment.createSession(pushed.absolutePath, options)
                } else {
                    environment.createSession(
                        context.assets.open(MODEL_NAME).use { it.readBytes() },
                        options,
                    )
                }
                logger.info(
                    TAG,
                    "detector loaded in ${System.currentTimeMillis() - started}ms" +
                        if (pushed.exists()) " (pushed override)" else " (bundled)",
                )
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

    private fun run(session: OrtSession, frame: Bitmap, floor: Float): List<RawDetection> {
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

                return collect(labels, boxes, scores, frame, floor)
            }
        } finally {
            image.close()
            sizes.close()
        }
    }

    /** Drops what scores too low or is too small to be lettering. */
    private fun collect(
        labels: LongArray,
        boxes: Array<FloatArray>,
        scores: FloatArray,
        frame: Bitmap,
        floor: Float,
    ): List<RawDetection> = labels.indices.mapNotNull { index ->
        if (scores[index] < floor) return@mapNotNull null
        val box = boxes[index].toBounds(frame) ?: return@mapNotNull null
        RawDetection(labels[index].toInt(), scores[index], box)
    }

    /**
     * Matches each piece of lettering to the balloon it sits in.
     *
     * By containment rather than by overlap-over-union: a balloon is much larger
     * than its text, so IoU scores a correct pairing low. The question worth
     * asking is whether the text is *inside* the balloon.
     */
    internal fun pair(detections: List<RawDetection>): List<DetectedBubble> {
        val balloons = detections.filter { it.label == LABEL_BALLOON }.map { it.box }

        val inBubbles = detections.filter { it.label == LABEL_TEXT_IN_BUBBLE }.map { detection ->
            DetectedBubble(
                text = detection.box,
                balloon = balloons.firstOrNull { detection.box.mostlyInside(it) },
            )
        }

        // Lettering on the art comes through as well. It was dropped here once,
        // and counting it on real pages showed that this cost two whole pages —
        // one of them laid out without balloons at all — to spare two sound
        // effects. The sound effects are separated after reading instead, where
        // the text itself can be looked at (`SoundEffect`).
        //
        // Minus what is already accounted for. The model will label one region
        // twice, and on `jap-mag-05` it returns the same 231x474 box as both
        // class 1 and class 2 — which `BubbleSignatureTest` caught as two
        // balloons sharing a signature, and which would otherwise be read,
        // translated and drawn twice. Free means free: not inside a balloon and
        // not another name for lettering already found in one.
        val onArt = detections.asSequence()
            .filter { it.label == LABEL_TEXT_FREE }
            .filterNot { free -> balloons.any { free.box.mostlyInside(it) } }
            .filterNot { free ->
                inBubbles.any { free.box.mostlyInside(it.text) || it.text.mostlyInside(free.box) }
            }
            .map { DetectedBubble(text = it.box, balloon = null, onArt = true) }
            .toList()

        return inBubbles + onArt
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

    internal companion object {
        const val TAG = "BubbleDetector"
        const val MODELS_DIR = "models"
        const val MODEL_NAME = "detector.onnx"

        const val INPUT = 640
        const val SCORE_FLOOR = 0.5f

        const val LABEL_BALLOON = 0
        const val LABEL_TEXT_IN_BUBBLE = 1

        /**
         * Lettering drawn straight onto the art — sound effects, but also
         * unboxed dialogue and whole pages set without balloons.
         *
         * Read out of the model and then deliberately dropped by [pair], so
         * `ドドド` does not become 咚咚咚 under an opaque box on the artwork.
         * How much of this class is really sound effects has never been
         * counted, which is what [detectRaw] is there to make countable.
         */
        const val LABEL_TEXT_FREE = 2

        /** Below this a box is a misdetection, not lettering. */
        const val MIN_SIDE = 8

        /** How much of the lettering must sit in a balloon to call it its own. */
        const val CONTAINED_PERCENT = 80
    }
}

/**
 * One box exactly as the model reported it.
 *
 * Deliberately not [DetectedBubble]: that type is the pipeline's, and says
 * "lettering, and the balloon around it". This one is the model's, and says
 * "a box, this class, this confidence" — including the classes the pipeline
 * throws away. Only measurement should ever see it.
 */
internal data class RawDetection(
    val label: Int,
    val score: Float,
    val box: TextBounds,
)
