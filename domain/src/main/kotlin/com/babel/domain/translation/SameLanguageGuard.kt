package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import com.babel.domain.vision.JapaneseScript
import com.babel.domain.vision.KoreanScript

/**
 * Text already in the reader's language is not sent at all: a source said to
 * be the target, or — the source unknown — Chinese text for a Chinese reader.
 *
 * A chat model is asked to echo text it cannot translate, and the result is
 * `Unchanged` only when the echo is exact. Short Chinese labels came back
 * rewritten instead: on a Chinese comic app the header 「历史」 was drawn over
 * as 「万岁」, and every title lost its 「[16P]」. ML Kit identifies the
 * language first and needs none of this; this asks the same question for any
 * provider, by script, before anything leaves the device.
 *
 * Only when the source language was not given — a game that said its text is
 * Japanese has kanji-only names (「風紀委員会」) that are still Japanese — and
 * only for Han without kana or hangul, which no other target shares.
 */
class SameLanguageGuard(private val delegate: Translator) : Translator {

    override val id: ProviderId = delegate.id

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean = delegate.supports(source, target)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val languages = request.languages
        val text = request.sourceText
        val chineseTarget = languages.target.value.lowercase().let { it == "zh" || it.startsWith("zh-") }
        // Or a source said to be the target already: manga mode calls a page
        // of Han without kana Chinese, and a Chinese comic app's list came
        // back rewritten — 「[16P]一人之下」 as 「「16P」之下」.
        val sameLanguage = languages.source?.let { primary(it) == primary(languages.target) } == true
        val alreadyChinese = sameLanguage || chineseTarget && languages.source == null &&
            text.any { it in HAN } && !JapaneseScript.isPresentIn(text) && !KoreanScript.isPresentIn(text) &&
            text.count { it in HAN } * 2 >= text.count { it.isLetter() }
        if (!alreadyChinese) {
            val result = delegate.translate(request)
            // Or an answer still in the source's script: a chat's misread
            // 「ちょとこまって」 came back as 「ちょっと困って」 — corrected, not
            // translated — and was drawn as the translation. Asked once more
            // without the context, which is what it was matching.
            // Or with hiragana left in it, which Chinese never uses: 「…49.4%
            // 质量算/さらに攻击力提升26.6%」 kept the 「さ」 of a line's wrap.
            if (chineseTarget && result.status == TranslationStatus.Translated && JapaneseScript.isPresentIn(text) &&
                (mostlyKana(result.translatedText) || result.translatedText.any { it in 'ぁ'..'ゖ' })
            ) {
                val again = delegate.translate(request.copy(context = null))
                if (again.status != TranslationStatus.Translated || !mostlyKana(again.translatedText)) return again
            }
            // Or handed back as it was, kana and all: 「……モモカ。」, a line
            // of dialogue that is only a name, was echoed with the story as
            // context, and the line stood untranslated. Kana is never the
            // reader's language when the reader is not Japanese.
            if (result.status == TranslationStatus.Unchanged && primary(languages.target) != "ja" &&
                text.any { it in KANA } && request.context != null
            ) {
                return delegate.translate(request.copy(context = null))
            }
            // Or in a third language: a skill's 「HPを8251増加/さらに回復率を
            // 26.6%増加」 came back 「HP增加8251/ furthermore, increase the
            // recovery value by 26.6%」, half of it English. Asked once more.
            if (chineseTarget && result.status == TranslationStatus.Translated && JapaneseScript.isPresentIn(text) &&
                mostlyLatinWords(result.translatedText) > mostlyLatinWords(text)
            ) {
                val again = delegate.translate(request.copy(context = null))
                if (again.status == TranslationStatus.Translated && mostlyLatinWords(again.translatedText) <= mostlyLatinWords(text)) return again
            }
            return result
        }
        return TranslationResult(
            requestId = request.requestId,
            elementId = request.elementId,
            revision = request.revision,
            originalText = text,
            translatedText = text,
            detectedSourceLanguage = languages.target,
            provider = id,
            status = TranslationStatus.Unchanged,
        )
    }

    /** Latin words of three letters or more: 「furthermore」, not 「HP」 or 「EX」. */
    private fun mostlyLatinWords(text: String): Int =
        Regex("[A-Za-z]{3,}").findAll(text).count()

    /** Kana for a third or more of the letters: Japanese, not Chinese with a 「の」 in it. */
    private fun mostlyKana(text: String): Boolean {
        val letters = text.count { it.isLetter() }
        val kana = text.count { it in '぀'..'ヿ' }
        return kana >= 2 && kana * 3 >= letters
    }

    private fun primary(tag: LanguageTag): String = tag.value.lowercase().substringBefore('-')

    private companion object {
        val HAN = '一'..'鿿'
        val KANA = '぀'..'ヿ'
    }
}
