package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TextSourceType
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus

/**
 * Keeps a game's Latin-letter words — the name the player typed, mostly —
 * out of the provider's hands.
 *
 * Asked in words to keep them, a chat model still read 「yizhiteamo」 as
 * Chinese pinyin and English: 「一支队伍酱」 ("a team"), 「一直team君」, or
 * dropped it. So each such word is sent as a numbered mark the model has no
 * reason to translate, and put back into the answer. Where a mark does not
 * come back the answer is used as it is — the same as before.
 *
 * Only for game text with another script in it: in an English line every
 * word is Latin and is the line.
 */
class KeptWordsTranslator(private val delegate: Translator) : Translator {

    override val id: ProviderId = delegate.id

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean = delegate.supports(source, target)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val text = request.sourceText
        if (request.sourceType != TextSourceType.GAME) return delegate.translate(request)
        val words = WORD.findAll(text).map { it.value }.distinct().toList()
        val otherLetters = text.count { it.isLetter() && it.code >= 0x250 }
        if (words.isEmpty() || otherLetters < MIN_OTHER_LETTERS) return delegate.translate(request)

        var masked = text
        words.sortedByDescending { it.length }.forEachIndexed { i, word -> masked = masked.replace(word, mark(i)) }
        val order = words.sortedByDescending { it.length }
        val result = delegate.translate(request.copy(sourceText = masked))
        if (result.status != TranslationStatus.Translated) return result.copy(originalText = text)
        var restored = result.translatedText
        order.forEachIndexed { i, word ->
            restored = if (mark(i) in restored) {
                restored.replace(mark(i), word)
            } else if (masked.startsWith(mark(i))) {
                // Dropped, as the name a line opens with often is: 「{1}。緊急事態
                // という事で…」 came back without it. Put back where it stood.
                word + FULL_STOP_COMMA + restored
            } else {
                restored
            }
        }
        return result.copy(originalText = text, translatedText = restored)
    }

    private fun mark(i: Int) = "{${i + 1}}"

    private companion object {
        /** Four or more Latin letters or digits in a row, starting with a letter. */
        val WORD = Regex("[A-Za-z][A-Za-z0-9_]{3,}")

        /** Letters of another script that make a line not an English one. */
        const val MIN_OTHER_LETTERS = 2

        const val FULL_STOP_COMMA = "，"
    }
}
