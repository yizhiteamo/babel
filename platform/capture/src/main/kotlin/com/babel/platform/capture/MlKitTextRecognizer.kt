package com.babel.platform.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.babel.core.common.BabelLogger
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
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

    /** This client is built with [JapaneseTextRecognizerOptions]; it reads Japanese. */
    override val language: LanguageTag = LanguageTag("ja")


    private val recognizer by lazy {
        TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    }

    override suspend fun recognize(frame: Bitmap): List<RecognizedLine> = try {
        val result = recognizer.process(InputImage.fromBitmap(frame, 0)).await()

        result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                val text = line.text
                if (text.isBlank()) return@mapNotNull null

                RecognizedLine(
                    text = text,
                    bounds = box.toTextBounds(),
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

    private fun Rect.toTextBounds() = TextBounds(
        left = left,
        top = top,
        right = right,
        bottom = bottom,
        // Frames are captured from the display, so boxes are already in screen
        // coordinates.
        space = CoordinateSpace.SCREEN,
    )

    private companion object {
        const val TAG = "TextRecognizer"
    }
}
