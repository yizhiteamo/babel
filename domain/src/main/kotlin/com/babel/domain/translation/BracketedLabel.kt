package com.babel.domain.translation

import com.babel.core.model.LanguageTag

/**
 * A label of a word and a bracketed part after it — a game's 「ハナコ(水着)」,
 * a student and her outfit — translated as two words and put back together.
 *
 * Sent whole, the same shape came back in three shapes on one screen: 「花子(泳装)」,
 * 「星野（泳装）」 and 「泳装小优」, the brackets dropped and the outfit moved to
 * the front, though the model is told to keep them. Two words cannot be
 * rearranged, each part is kept in the cache on its own so 「ウイ」 reads the
 * same in every card and on her own page, and the brackets are the target
 * language's.
 */
object BracketedLabel {

    /** The word and the bracketed part, or null when [text] is not one word and a bracketed part. */
    fun split(text: String): Pair<String, String>? {
        val t = text.trim()
        if (t.length < 4 || t.last() !in CLOSING) return null
        val open = t.indexOfLast { it in OPENING }
        if (open <= 0 || t.count { it in OPENING } != 1 || t.count { it in CLOSING } != 1) return null
        val word = t.substring(0, open).trim()
        val part = t.substring(open + 1, t.length - 1).trim()
        if (word.isEmpty() || part.isEmpty() || (word + part).any { it in SENTENCE }) return null
        return word to part
    }

    /**
     * The word before a trailing mark a game adds to a label — the 「+」 of a
     * skill raised past its base, 「ノーマルスキル+」 — and the mark; null when
     * there is none. Sent whole, the same skill label came back 「普通技能」
     * plain and 「技能宝珠」 with the mark, on two students side by side.
     */
    fun marked(text: String): Pair<String, String>? {
        val t = text.trim()
        val mark = t.takeLastWhile { it in MARKS }
        var word = t.dropLast(mark.length).trimEnd()
        // A stray glyph read between the word and its mark — the card's 「+」
        // badge read twice, once as another letter — went to the translator
        // with the word, and 「ノーマルスキル」 came back 「普通技能也」. One
        // letter of another script than the word's before the mark is not
        // the word's.
        if (mark.isNotEmpty() && word.length >= 4 && script(word.last()) != script(word[word.length - 2]) &&
            word.dropLast(1).all { script(it) == script(word[0]) }
        ) word = word.dropLast(1)
        // Or the 「+」 itself read once more as a letter — 「ナ」 and 「十」 are
        // its cross: 「ノーマルスキルナ+」 came back 「普通技能也+」.
        if (mark.isNotEmpty() && word.length >= 4 && word.last() in CROSS) word = word.dropLast(1)
        return if (mark.isEmpty() || word.length < 2 || word.any { it in MARKS || it in SENTENCE }) null else word to mark
    }

    private fun script(c: Char): Int = when (c) {
        in '぀'..'ゟ' -> 1
        in '゠'..'ヿ' -> 2
        in '一'..'鿿' -> 3
        else -> if (c.isLetterOrDigit()) 4 else 5
    }

    /** [word] and [part] as one label, in the brackets [target] is written with. */
    fun join(word: String, part: String, target: LanguageTag): String =
        if (target.value.substringBefore('-') in FULL_WIDTH_LANGUAGES) "$word（$part）" else "$word ($part)"

    private const val MARKS = "+＋"
    private const val CROSS = "ナ十"
    private const val OPENING = "(（"
    private const val CLOSING = ")）"
    private const val SENTENCE = "。、？！?!…「」『』"
    private val FULL_WIDTH_LANGUAGES = setOf("zh", "ja", "ko")
}
