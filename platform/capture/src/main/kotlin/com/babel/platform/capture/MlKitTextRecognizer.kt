package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.babel.core.common.BabelLogger
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
import com.babel.domain.vision.JapaneseScript
import com.babel.domain.vision.RecognizedLine
import com.babel.domain.vision.TextOrientationDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import javax.inject.Inject
import javax.inject.Singleton
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
@Singleton
internal class MlKitTextRecognizer @Inject constructor(
    private val logger: BabelLogger,
) : TextRecognizer {

    /**
     * This client is built with [JapaneseTextRecognizerOptions], but that model
     * reads Latin script alongside Japanese — so what it produces is only
     * Japanese when it actually says so in kana ([JapaneseScript]).
     */
    override fun languageOf(text: String): LanguageTag? =
        JAPANESE.takeIf { JapaneseScript.isPresentIn(text) }


    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
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
        return if (pixels * SCALE * SCALE <= MAX_PIXELS) SCALE else 1
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

        /** Doubling won the comparison; see [scaleFor]. */
        const val SCALE = 2

        /** Roughly 8.5 megapixels, so a doubled 1080x1920 frame still fits. */
        const val MAX_PIXELS = 8_500_000L
    }
}
