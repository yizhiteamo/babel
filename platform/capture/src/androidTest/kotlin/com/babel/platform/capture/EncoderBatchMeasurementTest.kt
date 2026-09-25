package com.babel.platform.capture

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Whether encoding several balloons in one call beats encoding them one by one.
 *
 * The cost table says the encoder is ~150ms per balloon and constant — about
 * 1.2s of the 3.1s a page of eight takes, and the half a key/value cache would
 * not touch (`docs/milestones/v2.md`). The exported graph declares a dynamic
 * `batch_size`, while `MangaOcrRecognizer` hard-codes `[1, 3, 224, 224]`, so
 * the batched call is available for nothing more than building a wider tensor.
 *
 * Whether it is *worth* it is a different question, and it is the one this
 * asks. Two things decide it:
 *
 * - **time** — a batch shares the per-call overhead and gives the runtime more
 *   to schedule, but the arithmetic does not go away. If it only saves the
 *   call overhead there is nothing here.
 * - **memory** — a batch of N holds N sets of encoder activations. Reading two
 *   balloons *concurrently* was measured at +93MB and rejected on exactly this
 *   ground, and a batch is the same kind of cost. The same yardstick is used
 *   here so the two numbers can be compared: native heap on a fresh process.
 *
 * There is a third cost this cannot measure and the caller must weigh: a page
 * is published balloon by balloon so the first translation appears in about a
 * second and a half, and encoding the page up front delays that. Chunking is
 * the answer if the batch wins, which is why the sizes here stop at eight.
 *
 * ## Measured, and the answer is no
 *
 * | Batch | One by one | Batched | Native heap |
 * |---|---|---|---|
 * | 2 | 285ms | 294ms (**3% slower**) | 228MB to 228MB |
 * | 4 | 604ms | 583ms (3% faster) | 228MB to **360MB** |
 * | 8 | 1190ms | 1142ms (4% faster) | 360MB to 360MB |
 *
 * The encoder is compute-bound, not call-bound: 150ms a balloon is arithmetic,
 * and `SessionOptions` already uses every core, so a batch has nothing left to
 * win. **+132MB for 3%** is a worse trade than the concurrency it resembles,
 * which was +93MB for 23% and was rejected.
 *
 * Kept as the evidence, the way `BubbleSignature` is. Anything that revisits
 * batching has to explain these numbers first; the lever on the encoder is a
 * smaller input or a different execution provider, not a wider one.
 *
 * Prints; asserts nothing.
 *
 * ```
 * adb shell am instrument -w -e class \
 *   com.babel.platform.capture.EncoderBatchMeasurementTest \
 *   com.babel.platform.capture.test/androidx.test.runner.AndroidJUnitRunner
 * adb logcat -d | grep ENCBATCH
 * ```
 */
@RunWith(AndroidJUnit4::class)
class EncoderBatchMeasurementTest {

    @Test
    fun measureBatchedAgainstSequentialEncoding() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(File(context.getExternalFilesDir(null), "models"), ENCODER)
        if (!model.exists()) {
            println("ENCBATCH skipped: needs $ENCODER in this package's files/models")
            return@runBlocking
        }

        val environment = OrtEnvironment.getEnvironment()
        val session = environment.createSession(model.absolutePath, OrtSession.SessionOptions())
        println("ENCBATCH input names ${session.inputNames}, outputs ${session.outputNames}")
        println("ENCBATCH native heap after load: ${nativeHeapMb()}MB")

        // Warm: the first run pays for arena allocation and would be charged to
        // whichever half went first.
        runBatch(environment, session, 1)
        runBatch(environment, session, 1)

        for (size in SIZES) {
            val baseHeap = nativeHeapMb()

            var oneByOne = 0L
            repeat(REPEATS) {
                val started = System.nanoTime()
                repeat(size) { runBatch(environment, session, 1) }
                oneByOne += System.nanoTime() - started
            }
            val sequentialHeap = nativeHeapMb()

            var batched = 0L
            repeat(REPEATS) {
                val started = System.nanoTime()
                runBatch(environment, session, size)
                batched += System.nanoTime() - started
            }
            val batchedHeap = nativeHeapMb()

            val one = oneByOne / REPEATS / 1_000_000
            val many = batched / REPEATS / 1_000_000
            val saved = if (one > 0) (one - many) * 100 / one else 0
            println(
                "ENCBATCH batch $size: one by one ${one}ms, batched ${many}ms " +
                    "($saved%), per balloon ${one / size}ms vs ${many / size}ms",
            )
            println(
                "ENCBATCH   native heap ${baseHeap}MB base, ${sequentialHeap}MB after " +
                    "sequential, ${batchedHeap}MB after batched",
            )
        }

        session.close()
    }

    /** One encoder pass over [size] blank crops, which cost what real ones do. */
    private fun runBatch(environment: OrtEnvironment, session: OrtSession, size: Int) {
        val buffer = FloatBuffer.allocate(size * 3 * INPUT * INPUT)
        // Content is irrelevant to the encoder's cost: it is a fixed-size
        // vision transformer, so every 224x224 input takes the same work.
        while (buffer.hasRemaining()) buffer.put(0f)
        buffer.rewind()

        val shape = longArrayOf(size.toLong(), 3, INPUT.toLong(), INPUT.toLong())
        val pixels = OnnxTensor.createTensor(environment, buffer, shape)
        try {
            session.run(mapOf("pixel_values" to pixels)).close()
        } finally {
            pixels.close()
        }
    }

    private fun nativeHeapMb() = Debug.getNativeHeapAllocatedSize() / 1024 / 1024

    private companion object {
        const val ENCODER = "encoder_model_int8.onnx"
        const val INPUT = 224

        /** Stops at eight: a page rarely has more, and a batch that large
         *  already delays the first translation by the whole page. */
        val SIZES = listOf(2, 4, 8)

        /** Enough to average out the scheduler without a long run. */
        const val REPEATS = 3
    }
}
