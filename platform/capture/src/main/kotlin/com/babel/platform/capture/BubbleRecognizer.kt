package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.LanguageTag
import com.babel.domain.vision.RecognizedLine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a cropped balloon with the comic recogniser when it is present, and with
 * the general one when it is not.
 *
 * Kept separate from [TextRecognizer] itself because the two engines are not
 * interchangeable: manga-ocr expects a **cropped balloon** and would read a
 * whole screen as one run-on string, so it is only ever reached through
 * [DetectingPageReader]. The page-wide path stays on ML Kit whatever is
 * installed, which is also what makes "no models present" a working
 * configuration rather than a broken one.
 */
@Singleton
internal class BubbleRecognizer @Inject constructor(
    private val manga: MangaOcrRecognizer,
    private val general: MlKitTextRecognizer,
) : TextRecognizer {

    private val current: TextRecognizer get() = if (manga.isAvailable) manga else general

    override fun languageOf(text: String): LanguageTag? = current.languageOf(text)

    override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
        val chosen = current
        val lines = chosen.recognize(frame)

        // manga-ocr reading nothing is not proof the balloon is empty — it
        // declines on crops it cannot make sense of, where ML Kit often still
        // finds something. Falling back costs one extra recognition on the
        // balloons that would otherwise have gone untranslated.
        return if (lines.isEmpty() && chosen !== general) general.recognize(frame) else lines
    }

    suspend fun release() = manga.release()
}
