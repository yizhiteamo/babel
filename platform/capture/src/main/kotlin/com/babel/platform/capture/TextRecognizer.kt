package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.LanguageTag
import com.babel.domain.vision.RecognizedLine

/**
 * Reads text out of a captured frame.
 *
 * Engine types stay behind this interface for the same reason translation
 * providers do (ADR 005): the pipeline must not change shape when the
 * recogniser is replaced. That matters here — `docs/milestones/v2.md` records
 * that OCR accuracy is V2's known quality ceiling, so swapping engines is a
 * foreseeable change, not a hypothetical one.
 */
internal interface TextRecognizer {

    /**
     * The language this engine reads. Stamped onto every element it produces,
     * because knowing beats detecting: language identification run on OCR
     * output attributed Japanese manga to Finnish (`docs/milestones/v2.md`).
     */
    val language: LanguageTag


    /**
     * @return lines in whatever order the engine produced them. Ordering and
     *   grouping are not this layer's job — see
     *   [com.babel.domain.vision.TextRegionGrouper].
     */
    suspend fun recognize(frame: Bitmap): List<RecognizedLine>
}
