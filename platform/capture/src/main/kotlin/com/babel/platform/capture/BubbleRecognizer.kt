package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.LanguageTag
import com.babel.domain.vision.JapaneseScript
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
 *
 * They also differ in what they do when asked the wrong question. ML Kit
 * returns nothing; manga-ocr returns confident nonsense. Both answers are
 * handled here, and for a while only the first one was.
 */
@Singleton
internal class BubbleRecognizer @Inject constructor(
    private val manga: BalloonEngine,
    private val general: TextRecognizer,
) : TextRecognizer {

    private val current: TextRecognizer get() = if (manga.isAvailable) manga else general

    override fun languageOf(text: String): LanguageTag? = current.languageOf(text)

    override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
        val chosen = current
        val lines = chosen.recognize(frame)
        if (chosen === general) return lines

        // manga-ocr reading nothing is not proof the balloon is empty — it
        // declines on crops it cannot make sense of, where ML Kit often still
        // finds something. Falling back costs one extra recognition on the
        // balloons that would otherwise have gone untranslated.
        //
        // Reading the *wrong* thing needs the same fallback and did not have
        // it. manga-ocr does not fail on a language it was not built for: given
        // the English balloons on `jap-mag-08` it returned
        // `WindrisntthatSamantha2Thebig.hatThettbooksting...`, which was then
        // translated and drawn in an opaque box over text the reader could
        // already read. Worse than leaving the page alone, and invisible to a
        // check that only asks whether anything came back.
        //
        // The test is on the script rather than on the words: a Japanese
        // recogniser that produces neither kana nor Han has not read Japanese,
        // whatever it produced instead. ML Kit's Japanese model reads Latin
        // too, which is what makes it the right second opinion here.
        val unusable = lines.isEmpty() ||
            lines.none { JapaneseScript.couldBeJapanese(it.text) }
        return if (unusable) general.recognize(frame) else lines
    }

    suspend fun release() = manga.release()
}
