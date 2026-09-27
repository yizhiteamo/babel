package com.babel.domain.vision

/**
 * Whether a piece of recognised text is actually written in Japanese.
 *
 * A recogniser is built for one language, and telling the pipeline which beats
 * letting it guess — language identification run on OCR output attributed
 * Japanese manga to Finnish (`docs/milestones/v2.md`). But "the engine reads
 * Japanese" and "this line is Japanese" are different claims, and only the
 * first one is certain in advance.
 *
 * ML Kit's Japanese recogniser reads Latin script too, so in manga mode an
 * English page came back stamped `ja` and was translated ja→zh. Reported from a
 * device as manga mode ruining plain-text translation.
 *
 * ## Why kana, and not any CJK character
 *
 * Kana are Japanese and nothing else. Han characters are shared with Chinese,
 * so a Han-only line is genuinely ambiguous — claiming `ja` for it would swap
 * one confident mistake for another, on a user whose target language is often
 * Chinese. Ambiguous text is handed to detection instead, which is what
 * detection is for.
 *
 * The narrower rule costs nothing on the material this was built for: all
 * fifteen transcribed bubbles in `comic-sample` contain kana, as Japanese
 * dialogue essentially always does.
 */
object JapaneseScript {

    private val HIRAGANA = '぀'..'ゟ'
    private val KATAKANA = '゠'..'ヿ'
    private val HAN = '一'..'鿿'

    /** True when [text] contains at least one kana character. */
    fun isPresentIn(text: String): Boolean =
        text.any { it in HIRAGANA || it in KATAKANA }

    /**
     * Whether [text] could be Japanese **writing** at all — kana or Han.
     *
     * A different question from [isPresentIn], deliberately, and the difference
     * is the whole point of having both. That one asks "is this line Japanese",
     * where a Han-only line is genuinely ambiguous and claiming `ja` for it
     * would be a confident mistake. This one asks "did a Japanese recogniser
     * produce Japanese script", where a Han-only answer is obviously yes.
     *
     * It exists because manga-ocr does not fail on text it was not built for.
     * Given English balloons it returns fluent-looking nonsense —
     * `WindrisntthatSamantha2Thebig.hatThettbooksting...` off a real page — and
     * that nonsense was translated and drawn over readable English
     * (`docs/milestones/v2.md`). Empty output already had a fallback; wrong
     * output did not, because nothing was asking this question.
     */
    fun couldBeJapanese(text: String): Boolean =
        text.any { it in HIRAGANA || it in KATAKANA || it in HAN }

    /**
     * Katakana middle dot — punctuation, not a letter.
     *
     * It lives in the katakana block, so counting the block wholesale makes it
     * evidence of Japanese. It is not: measured on a Chinese page, four of the
     * six "kana" manga-ocr produced were this character, standing in for the
     * `…` and `·` the artwork actually used.
     */
    private const val MIDDLE_DOT = '・'

    /**
     * Kana as a share of the CJK characters in [text], ignoring everything that
     * is neither kana nor Han.
     *
     * Returns 0 when there is no CJK at all, which reads as "no evidence of
     * Japanese" and is what the callers want.
     */
    fun kanaRatio(text: String): Float {
        var kana = 0
        var han = 0
        for (c in text) {
            when {
                c == MIDDLE_DOT -> Unit
                c in HIRAGANA || c in KATAKANA -> kana++
                c in HAN -> han++
            }
        }
        val total = kana + han
        return if (total == 0) 0f else kana.toFloat() / total
    }

    /**
     * Whether this line is Japanese **prose**, as against a line that merely
     * has a kana character in it.
     *
     * [isPresentIn] is the older, weaker question, and OCR noise is what makes
     * the difference matter. A Japanese recogniser given Chinese artwork does
     * not refuse: it returns Han characters mapped into its own vocabulary,
     * with the occasional kana hallucinated among them. Five of twenty-four
     * balloons on a Chinese page came back holding a kana, and every one of
     * them was stamped `ja` and translated ja→zh.
     *
     * Measured over four pages, the two populations do not overlap and are not
     * close to overlapping:
     *
     * | | lowest | highest |
     * |---|---|---|
     * | Japanese balloons (46 of them) | **44.4%** | 100% |
     * | Chinese balloons (37 of them) | 0% | **11.8%** |
     *
     * [MIN_KANA_RATIO] sits in that gap. It is deliberately nowhere near either
     * edge: the point is that no threshold in the whole range 12%–44% would
     * have behaved differently on the material, so the exact value carries no
     * weight and should not be tuned without new measurements.
     */
    fun isJapanese(text: String): Boolean = kanaRatio(text) >= MIN_KANA_RATIO

    /** See [isJapanese] for the measurement this comes from. */
    const val MIN_KANA_RATIO = 0.25f

    /** Shared with Chinese, which is the whole difficulty. */
    fun isHan(c: Char): Boolean = c in HAN
}
