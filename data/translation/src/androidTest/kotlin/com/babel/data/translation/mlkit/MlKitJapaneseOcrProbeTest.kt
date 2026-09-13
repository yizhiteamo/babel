package com.babel.data.translation.mlkit

import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feasibility probe for V2, not a V2 feature test.
 *
 * Vertical Japanese OCR is the biggest unknown in manga translation: if it does
 * not work, the acquisition design has to change, so it is settled before any
 * V2 code is written — the same order that saved V1 from committing to an
 * unverified translation provider.
 *
 * Two things are under test at once, and they can fail independently:
 *  - whether ML Kit's Japanese recognizer runs at all on this device, which has
 *    no Google Play Services while the Japanese model is distributed through it
 *  - whether it reads text set vertically, as manga is
 *
 * Failures here are reported, not worked around.
 *
 * ## What this probe actually found
 *
 * Both answers are yes — it runs without Play Services and it reads vertical
 * text, at 83% average character overlap. But the *shape* of the output is what
 * matters for V2, and it is not usable as-is:
 *
 *  - **Columns come back in the wrong order.** `今日はいい天気` was returned as
 *    `いい天気今日は`. Japanese runs right-to-left vertically; ML Kit emits
 *    left-to-right. Reading order must be rebuilt from the boxes' x coordinates.
 *  - **One bubble is not one block.** The first bubble split into two blocks.
 *    Bounding boxes are per *column* (38–95px wide), never per bubble.
 *  - **Whole columns can vanish.** `明日の会議` was missed entirely.
 *  - **Characters get confused** between visually similar forms: 駅→統, 待→侍.
 *
 * So a text block cannot be treated as a translation unit. V2 has to group
 * columns into bubbles and order them itself, which is exactly why
 * `docs/features/v2-manga-translation.md` lists "text-region detection" and
 * "speech-bubble-aware layout" as separate scope items — this probe confirms
 * they are load-bearing, not optional polish.
 */
@RunWith(AndroidJUnit4::class)
class MlKitJapaneseOcrProbeTest {

    /** Text drawn into `manga-sample.png`, one entry per speech bubble. */
    private val expectedBubbles = listOf(
        "おはようございます",
        "今日はいい天気",
        "明日の会議何時ですか",
        "駅前で待ってます",
    )

    @Test
    fun recognisesVerticalJapaneseInSpeechBubbles() = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = assets.open(SAMPLE).use(BitmapFactory::decodeStream)
        checkNotNull(bitmap) { "could not decode $SAMPLE" }

        val recognizer = TextRecognition.getClient(
            JapaneseTextRecognizerOptions.Builder().build(),
        )

        val result = try {
            withTimeout(MODEL_TIMEOUT_MS) {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            }
        } catch (failure: Exception) {
            throw AssertionError(
                "Japanese OCR could not run on this device. The Japanese model " +
                    "ships through Google Play Services, which is absent here. " +
                    "Cause: $failure",
                failure,
            )
        } finally {
            recognizer.close()
        }

        val blocks = result.textBlocks
        val recognised = blocks.joinToString(" | ") { it.text.replace("\n", "") }

        println("OCR_PROBE blocks=${blocks.size}")
        blocks.forEachIndexed { index, block ->
            println("OCR_PROBE [$index] box=${block.boundingBox} text=${block.text.replace("\n", "")}")
        }

        assertTrue(blocks.isNotEmpty(), "no text blocks found at all")

        // Character-level overlap rather than exact equality: OCR is allowed to
        // mis-segment lines, but if vertical text were unreadable almost no
        // expected character would appear.
        val flattened = recognised.filterNot(Char::isWhitespace).toSet()
        val perBubble = expectedBubbles.map { expected ->
            val chars = expected.toSet()
            val hit = chars.count { it in flattened }
            expected to hit.toDouble() / chars.size
        }
        perBubble.forEach { (expected, ratio) ->
            println("OCR_PROBE match ${(ratio * 100).toInt()}%  $expected")
        }

        val averageMatch = perBubble.sumOf { it.second } / perBubble.size
        println("OCR_PROBE average=${(averageMatch * 100).toInt()}%")

        assertTrue(
            averageMatch >= ACCEPTABLE_MATCH,
            "vertical Japanese barely recognised: average character overlap " +
                "${(averageMatch * 100).toInt()}%, recognised text was: $recognised",
        )
    }

    private companion object {
        const val SAMPLE = "manga-sample.png"
        const val MODEL_TIMEOUT_MS = 180_000L

        /** Low enough to tolerate mis-segmentation, high enough to fail if vertical text is unreadable. */
        const val ACCEPTABLE_MATCH = 0.6
    }
}
