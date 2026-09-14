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

    /** True when [text] contains at least one kana character. */
    fun isPresentIn(text: String): Boolean =
        text.any { it in HIRAGANA || it in KATAKANA }
}
