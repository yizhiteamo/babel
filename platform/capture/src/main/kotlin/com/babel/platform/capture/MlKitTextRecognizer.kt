package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.babel.core.common.BabelLogger
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
import com.babel.domain.vision.JapaneseScript
import com.babel.domain.vision.KoreanScript
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextOrientationDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * ML Kit on-device text recognition.
 *
 * Reads at **line** granularity, not block: a block can hold two columns
 * concatenated in the wrong order, while its lines keep them apart. That is the
 * only reason reading order is recoverable at all — see `docs/milestones/v2.md`.
 *
 * Recognition is on-device, so frames never leave the machine.
 */
internal class MlKitTextRecognizer(
    private val logger: BabelLogger,
    private val script: Script,
) : TextRecognizer {

    /**
     * Which of ML Kit's models this instance is. One class rather than two
     * because everything below the model — enlarging, line mapping, coordinate
     * correction — is the same work, and two copies of it is how they come to
     * differ.
     */
    internal enum class Script { JAPANESE, KOREAN }

    /**
     * Each model reads Latin script alongside its own, so neither may claim its
     * language merely because it returned something.
     *
     * The two claims are not equally hard. Japanese needs a ratio, because a
     * Han-only line is shared with Chinese ([JapaneseScript]). Korean needs one
     * character: Hangul is shared with nothing ([KoreanScript]).
     */
    override fun languageOf(text: String): LanguageTag? = when (script) {
        Script.JAPANESE -> JAPANESE.takeIf { JapaneseScript.isJapanese(text) }
        Script.KOREAN -> KOREAN.takeIf { KoreanScript.isPresentIn(text) }
    }

    private val recognizer by lazy {
        TextRecognition.getClient(
            when (script) {
                Script.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
                Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            },
        )
    }

    override suspend fun recognize(frame: Bitmap): List<RecognizedLine> = try {
        val scale = scaleFor(frame)
        val enlarged = if (scale > 1) frame.enlargedBy(scale) else frame

        val result = try {
            recognizer.process(InputImage.fromBitmap(enlarged, 0)).await()
        } finally {
            if (enlarged !== frame) enlarged.recycle()
        }

        result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text
                if (text.isBlank()) return@mapNotNull null

                RecognizedLine(
                    text = text,
                    // Back to the frame's own coordinates: the engine measured
                    // an enlarged copy, and boxes twice their true size would
                    // put every translation in the wrong place.
                    bounds = box.toTextBounds(scale),
                    // The engine's own angle beats any inference from shape, and
                    // is decisive where shape is not: a single character is
                    // square and tells shape nothing.
                    orientation = TextOrientationDetector.fromAngle(line.angle),
                )
            }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        // Recognition failing must degrade to "nothing found", never take the
        // capture session down with it.
        logger.warn(TAG, "recognition failed", failure)
        emptyList()
    }

    /**
     * Enlarging the frame before recognition, measured rather than assumed.
     *
     * A page shown on a phone gives glyphs around 27px, which is where ML Kit
     * starts confusing similar characters — 汗 read as 井, 験 as 線, 誘導 as
     * 絵尊 — and a sentence with two wrong characters translates to nonsense.
     *
     * Five variants were compared on real pages against hand-transcribed text
     * (`OcrPreprocessingExperimentTest`). Doubling was the only one that
     * improved **both** pages: 85%→91% and 82%→87%. Raising contrast and
     * re-reading magnified crops each helped one page and hurt the other, so
     * neither was taken.
     *
     * The cost is memory: a doubled 1080x1920 frame is about 33MB, held for the
     * length of one recognition. [MAX_PIXELS] keeps that bounded on larger
     * displays rather than trusting the number to stay small.
     */
    private fun scaleFor(frame: Bitmap): Int {
        val pixels = frame.width.toLong() * frame.height
        val shortest = minOf(frame.width, frame.height)

        // A balloon crop is not a page, and the doubling below was measured on
        // pages. Asked for a 38x45 crop, doubling gives 76x90, which this
        // engine declines outright — five of ten balloons on `kr-mag-01` came
        // back with nothing at all. There is room to ask for more: the pixel
        // budget is for a doubled full screen, and a crop that small could be
        // enlarged forty times inside it.
        val wanted = when {
            shortest <= 0 -> SCALE
            else -> maxOf(SCALE, TARGET_SHORTEST_SIDE / shortest)
        }.coerceAtMost(MAX_SCALE)

        var scale = wanted
        while (scale > 1 && pixels * scale * scale > MAX_PIXELS) scale--
        return scale
    }

    private fun Bitmap.enlargedBy(factor: Int): Bitmap =
        Bitmap.createScaledBitmap(this, width * factor, height * factor, true)

    private fun Rect.toTextBounds(scale: Int) = TextBounds(
        left = left / scale,
        top = top / scale,
        right = right / scale,
        bottom = bottom / scale,
        // Frames are captured from the display, so boxes are already in screen
        // coordinates.
        space = CoordinateSpace.SCREEN,
    )

    private companion object {
        const val TAG = "TextRecognizer"

        val JAPANESE = LanguageTag("ja")
        val KOREAN = LanguageTag("ko")

        /** Doubling won the comparison on whole pages; see [scaleFor]. */
        const val SCALE = 2

        /**
         * What a small crop is enlarged towards, on its shorter side.
         *
         * Measured on `kr-mag-01`, where doubling alone left five of ten
         * balloons unread. Enlarging towards this recovers the largest of them,
         * a 93x82 balloon — a real one, not a sound effect. Doubling the target
         * again to 640 recovers **nothing** further and costs 25–45% more time
         * per page, so the gain is here and no further.
         *
         * The four still declined are 38x45 down to 68x61: an ellipsis balloon
         * with nothing to read and the page's slanted sound effects. Those are
         * the engine's limit rather than a resolution problem.
         */
        const val TARGET_SHORTEST_SIDE = 320

        /** So a one-pixel sliver cannot ask for an absurd enlargement. */
        const val MAX_SCALE = 8

        /** Roughly 8.5 megapixels, so a doubled 1080x1920 frame still fits. */
        const val MAX_PIXELS = 8_500_000L
    }
}
