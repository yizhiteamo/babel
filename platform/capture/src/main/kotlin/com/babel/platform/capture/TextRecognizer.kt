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
     * What language [text] is in, as far as this engine can vouch for it, or
     * null to leave the question to detection.
     *
     * Knowing beats detecting — language identification run on OCR output
     * attributed Japanese manga to Finnish (`docs/milestones/v2.md`) — but the
     * engine can only vouch for what it actually read, not for what it is built
     * to read. ML Kit's Japanese recogniser also reads Latin, and stamping `ja`
     * on an English page had manga mode translating it ja→zh.
     */
    fun languageOf(text: String): LanguageTag?


    /**
     * @return lines in whatever order the engine produced them. Ordering and
     *   grouping are not this layer's job — see
     *   [com.babel.domain.vision.TextRegionGrouper].
     */
    suspend fun recognize(frame: Bitmap): List<RecognizedLine>
}

/**
 * A recogniser built for one balloon at a time, and able to say whether it can
 * run at all.
 *
 * Exists so [BubbleRecognizer] depends on the capability rather than on
 * manga-ocr specifically — which is what lets the fallback it performs be
 * tested with both engines under control. The weights arrive by download
 * (ADR 011), so "not available" is an ordinary state rather than a failure.
 */
internal interface BalloonEngine : TextRecognizer {

    /** Whether every piece of the model is on the device. */
    val isAvailable: Boolean

    /** Lets go of the loaded model. Recognising again reloads it. */
    suspend fun release()
}
