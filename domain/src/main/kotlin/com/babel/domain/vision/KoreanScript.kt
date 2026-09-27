package com.babel.domain.vision

/**
 * Whether recognised text is written in Hangul.
 *
 * The counterpart of [JapaneseScript], and a far easier question. Han
 * characters are shared between Japanese and Chinese, which is why deciding
 * between those two needed a ratio, a page and two rounds of measurement.
 * Hangul is shared with nothing: one syllable is proof.
 *
 * That asymmetry is why this is the **only** trustworthy signal for a Korean
 * page. Measured on `kr-mag-01`/`-02`: manga-ocr does not decline Korean
 * artwork, it invents Japanese for it — 「이부키, 뭐해?」 came back as
 * `olデヲル号おH?` and a whole balloon as
 * `それを考えなければ、今、2018年3月19日`, fluent and entirely fabricated.
 * Those readings are 75–80% kana, so neither [JapaneseScript.isPresentIn] nor
 * [JapaneseScript.isJapanese] can tell them from real Japanese. Nothing about
 * the Japanese engine's output can. Only a Korean engine finding Hangul can,
 * and when it does the answer is certain.
 */
object KoreanScript {

    /** Composed syllables — what ordinary Korean text is made of. */
    private val SYLLABLES = '가'..'힣'

    /** Conjoining jamo, and the compatibility jamo a recogniser may emit. */
    private val JAMO = 'ᄀ'..'ᇿ'
    private val COMPATIBILITY_JAMO = '㄰'..'㆏'

    /** True when [text] contains any Hangul at all. */
    fun isPresentIn(text: String): Boolean =
        text.any { it in SYLLABLES || it in JAMO || it in COMPATIBILITY_JAMO }
}
