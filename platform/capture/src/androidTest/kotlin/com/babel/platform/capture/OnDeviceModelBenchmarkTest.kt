package com.babel.platform.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Stage 2: can a phone actually run the comic detector and manga-ocr.
 *
 * The desktop numbers say the models are worth having — the detector found the
 * bubbles on all six pages and manga-ocr read one page exactly, where the
 * shipped pipeline manages 87%. None of that matters if a page takes a minute
 * on a handset.
 *
 * **The likeliest failure is the recogniser's decoder.** This export carries no
 * key/value cache, so every character generated re-runs the decoder over the
 * whole sequence so far. On a desktop that came to 1–1.7s for a page of eight
 * regions, which hides the cost; a phone is several times slower and the cost
 * multiplies by the number of characters.
 *
 * Nothing here touches the app. ONNX Runtime is an `androidTestImplementation`
 * dependency, so what users get is unchanged until the numbers justify moving
 * it.
 *
 * ## Reading the result
 *
 * Time matters, but **the recognised text matters more**: the same model on the
 * same page must produce the same characters here as it did on the desktop. If
 * it does not, the port is wrong and no timing figure from it means anything.
 *
 * The device is an x86_64 emulator, not an ARM handset. These figures indicate
 * a direction; they are not phone numbers.
 *
 * ```
 * adb push models/. /sdcard/Android/data/com.babel.platform.capture.test/files/models/
 * ```
 */
@RunWith(AndroidJUnit4::class)
class OnDeviceModelBenchmarkTest {

    @Test
    fun benchmarkDetectorAndRecogniser() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = context.getExternalFilesDir(null)
        val models = File(root, "models")
        val pages = File(root, "comic-sample")
            .listFiles { file -> file.extension.lowercase() == "jpg" }
            ?.sortedBy { it.name }
            .orEmpty()

        val detectorFile = File(models, "detector.onnx")
        if (!detectorFile.exists() || pages.isEmpty()) {
            println("BENCH skipped: push models to ${models.absolutePath} and pages beside them")
            return
        }

        val env = OrtEnvironment.getEnvironment()
        val loadStart = System.currentTimeMillis()
        val detector = env.createSession(detectorFile.absolutePath, OrtSession.SessionOptions())
        val encoder = env.createSession(
            File(models, "encoder_model_int8.onnx").absolutePath, OrtSession.SessionOptions(),
        )
        val decoder = env.createSession(
            File(models, "decoder_model_int8.onnx").absolutePath, OrtSession.SessionOptions(),
        )
        val vocab = File(models, "vocab.txt").readLines()
        println("BENCH loaded 3 sessions in ${System.currentTimeMillis() - loadStart}ms, vocab=${vocab.size}")
        println("BENCH native heap after load: ${Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB")

        for (page in pages) {
            val bitmap = BitmapFactory.decodeFile(page.absolutePath)
                ?.copy(Bitmap.Config.ARGB_8888, false) ?: continue

            val pageStart = System.currentTimeMillis()
            val detectStart = System.currentTimeMillis()
            val regions = detect(env, detector, bitmap)
            val detectMs = System.currentTimeMillis() - detectStart

            val texts = mutableListOf<Pair<String, Long>>()
            for (box in regions) {
                val crop = crop(bitmap, box) ?: continue
                val started = System.currentTimeMillis()
                val text = recognise(env, encoder, decoder, vocab, crop)
                crop.recycle()
                texts += text to (System.currentTimeMillis() - started)
            }

            val pageMs = System.currentTimeMillis() - pageStart
            println(
                "BENCH page=${page.name} ${bitmap.width}x${bitmap.height}" +
                    " detect=${detectMs}ms regions=${texts.size} total=${pageMs}ms" +
                    " nativeHeap=${Debug.getNativeHeapAllocatedSize() / 1024 / 1024}MB",
            )
            texts.forEach { (text, ms) -> println("BENCH    ${ms}ms \"$text\"") }

            bitmap.recycle()
        }

        decoder.close(); encoder.close(); detector.close()
    }

    /** Boxes of class 1 — text inside a bubble, which is what gets translated. */
    private fun detect(
        env: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap,
    ): List<IntArray> {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        scaled.recycle()

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

        val image = OnnxTensor.createTensor(env, buffer, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong()))
        // (width, height). The other order puts boxes past the right edge of
        // the page and makes a working detector look broken.
        val sizes = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())),
            longArrayOf(1, 2),
        )

        val result = session.run(mapOf("images" to image, "orig_target_sizes" to sizes))
        @Suppress("UNCHECKED_CAST")
        val labels = (result[0].value as Array<LongArray>)[0]
        @Suppress("UNCHECKED_CAST")
        val boxes = (result[1].value as Array<Array<FloatArray>>)[0]
        @Suppress("UNCHECKED_CAST")
        val scores = (result[2].value as Array<FloatArray>)[0]

        val kept = mutableListOf<IntArray>()
        for (index in labels.indices) {
            if (scores[index] < SCORE_FLOOR || labels[index] != 1L) continue
            kept += intArrayOf(
                boxes[index][0].toInt(), boxes[index][1].toInt(),
                boxes[index][2].toInt(), boxes[index][3].toInt(),
            )
        }

        result.close(); image.close(); sizes.close()
        return kept
    }

    private fun crop(bitmap: Bitmap, box: IntArray): Bitmap? {
        val left = box[0].coerceIn(0, bitmap.width)
        val top = box[1].coerceIn(0, bitmap.height)
        val right = box[2].coerceIn(0, bitmap.width)
        val bottom = box[3].coerceIn(0, bitmap.height)
        if (right - left < 4 || bottom - top < 4) return null
        return Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
    }

    /** Greedy decoding, re-running the decoder each step because there is no cache. */
    private fun recognise(
        env: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        vocab: List<String>,
        crop: Bitmap,
    ): String {
        val scaled = Bitmap.createScaledBitmap(crop, OCR_INPUT, OCR_INPUT, true)
        val pixels = IntArray(OCR_INPUT * OCR_INPUT)
        scaled.getPixels(pixels, 0, OCR_INPUT, 0, 0, OCR_INPUT, OCR_INPUT)
        scaled.recycle()

        val buffer = FloatBuffer.allocate(3 * OCR_INPUT * OCR_INPUT)
        for (channel in 0 until 3) {
            for (pixel in pixels) {
                // Greyscale then repeated across channels, as manga-ocr expects,
                // and scaled to [-1, 1].
                val grey = (Color.red(pixel) * 299 + Color.green(pixel) * 587 +
                    Color.blue(pixel) * 114) / 1000
                buffer.put((grey / 255f - 0.5f) / 0.5f)
            }
        }
        buffer.rewind()

        val pixelValues = OnnxTensor.createTensor(
            env, buffer, longArrayOf(1, 3, OCR_INPUT.toLong(), OCR_INPUT.toLong()),
        )
        val encoded = encoder.run(mapOf("pixel_values" to pixelValues))
        val hidden = encoded[0] as OnnxTensor

        val tokens = mutableListOf(CLS)
        // A `for` with `break`, not `repeat`: `return@repeat` would skip one
        // step rather than stop, so the loop would run its full length past the
        // end token and report a latency that is pure fiction.
        for (step in 0 until MAX_TOKENS) {
            val ids = OnnxTensor.createTensor(
                env,
                LongBuffer.wrap(tokens.map { it.toLong() }.toLongArray()),
                longArrayOf(1, tokens.size.toLong()),
            )
            val output = decoder.run(mapOf("input_ids" to ids, "encoder_hidden_states" to hidden))

            @Suppress("UNCHECKED_CAST")
            val logits = (output[0].value as Array<Array<FloatArray>>)[0].last()
            var best = 0
            for (index in logits.indices) if (logits[index] > logits[best]) best = index

            output.close(); ids.close()
            if (best == SEP) break
            tokens += best
        }

        encoded.close(); pixelValues.close()

        return tokens.drop(1)
            .mapNotNull { vocab.getOrNull(it) }
            .filterNot { it.startsWith("[") }
            .joinToString("")
    }

    private companion object {
        const val INPUT = 640
        const val OCR_INPUT = 224
        const val SCORE_FLOOR = 0.5f
        const val CLS = 2
        const val SEP = 3
        const val MAX_TOKENS = 96
    }
}
