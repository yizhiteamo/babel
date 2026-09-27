package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.LanguageTag
import com.babel.core.model.TextBounds
import com.babel.domain.vision.JapaneseScript
import com.babel.domain.vision.KoreanScript
import com.babel.domain.vision.RecognizedLine
import com.babel.platform.capture.di.KoreanEngine
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
    @KoreanEngine private val korean: TextRecognizer,
) : TextRecognizer {

    /**
     * Whether the page being read is Korean artwork, decided by [startPage].
     *
     * Volatile because the page is decided on one coroutine and the balloons
     * may be read on another.
     */
    @Volatile
    private var pageIsKorean = false

    /**
     * What [startPage] read off the whole frame, in frame coordinates, kept for
     * [pageLinesIn]. Null whenever the page is not Korean.
     */
    @Volatile
    private var pageLines: List<RecognizedLine>? = null

    private val current: TextRecognizer get() = if (manga.isAvailable) manga else general

    /**
     * Looks at the whole page once, and only to ask whether it is Korean.
     *
     * Nothing downstream can answer this. Both other engines are Japanese, and
     * manga-ocr does not decline artwork it cannot read — measured on
     * `kr-mag-01`/`-02`, it invented Japanese for every balloon:
     * 「이부키, 뭐해?」 came back `olデヲル号おH?`, and one balloon became
     * `それを考えなければ、今、2018年3月19日`, fluent and wholly fabricated.
     * Those readings are 75–80% kana, so every test this class already
     * applies — [JapaneseScript.couldBeJapanese] included — passes them, and
     * the fabrications were translated and drawn over the art. What the user
     * reported as misplaced balloons was this.
     *
     * Only positive evidence settles it, and only a Korean model can give it:
     * Hangul is shared with no other language, so one syllable is proof
     * ([KoreanScript]). Asked of the whole page rather than of one balloon,
     * because a page's first balloon may be a sound effect.
     *
     * One extra recognition per page, not per balloon. Japanese pages pay it
     * once and nothing else changes for them.
     *
     * ## Presence proves nothing; proportion does
     *
     * The first version asked whether the Korean model found *any* Hangul, and
     * every page in the sample came back yes — the model hallucinates Hangul on
     * Japanese and Chinese artwork just as manga-ocr hallucinates kana on
     * Korean. It is the same mistake as the one this class exists to catch,
     * made in the other direction, and it cost every Japanese page its reader:
     * `jap-mag-10` went from 21 translations to 13.
     *
     * The proportion separates them cleanly, measured over three pages:
     *
     * | page | Hangul / characters | |
     * |---|---|---|
     * | `kr-mag-01`, Korean | 48 / 51 | **94.1%** |
     * | `cn-mag-01`, Chinese | 16 / 43 | 37.2% |
     * | `jap-mag-10`, Japanese | 28 / 89 | 31.5% |
     *
     * [MIN_HANGUL_RATIO] sits in the gap, far from both edges. As with
     * `JapaneseScript.MIN_KANA_RATIO`, no value between 38% and 94% would have
     * behaved differently here, so the exact number carries no weight and
     * should not be tuned without new measurements.
     */
    suspend fun startPage(frame: Bitmap) {
        val lines = korean.recognize(frame)
        var hangul = 0
        var characters = 0
        for (line in lines) {
            for (c in line.text) {
                if (c.isWhitespace()) continue
                characters++
                if (KoreanScript.isPresentIn(c.toString())) hangul++
            }
        }
        pageIsKorean = characters >= MIN_PROBE_CHARS &&
            hangul.toFloat() / characters >= MIN_HANGUL_RATIO
        pageLines = if (pageIsKorean) lines else null
    }

    /**
     * The lines of [startPage]'s reading that fall inside [area], or null when
     * there is no such reading to offer.
     *
     * This exists because the probe's work was being thrown away. It reads the
     * **whole frame**, and a whole frame is a better thing to hand this engine
     * than a balloon cut out of it: measured on `kr-mag-01`, the page pass
     * returned **16 lines** while reading the same page one balloon at a time
     * produced **6 regions**. The crops it refuses are 38x45 to 68x61 — small
     * enough that cropping has taken away both the glyph edges and every clue
     * the layout gave.
     *
     * Null on any page that is not Korean, so the Japanese and Chinese paths
     * never see this and keep reading balloon by balloon.
     */
    fun pageLinesIn(area: TextBounds): List<RecognizedLine>? =
        pageLines?.filter { it.bounds.centreIsIn(area) }

    override fun languageOf(text: String): LanguageTag? =
        if (pageIsKorean) korean.languageOf(text) else current.languageOf(text)

    override suspend fun recognize(frame: Bitmap): List<RecognizedLine> {
        // Settled for the page: no Japanese engine is asked, because a wrong
        // answer from one is indistinguishable from a right one.
        if (pageIsKorean) return korean.recognize(frame)

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

    private companion object {
        /** See [startPage] for the measurement. */
        const val MIN_HANGUL_RATIO = 0.65f

        /**
         * Characters the probe needs before it will decide anything, so a page
         * showing two glyphs does not settle which reader the rest of it gets.
         */
        const val MIN_PROBE_CHARS = 10
    }
}
