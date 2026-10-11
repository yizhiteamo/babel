package com.babel.domain.translation

import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus

/**
 * Speakers' names in a line, sent as marks and put back as the translation
 * the name was given in its tab, so a name reads the same everywhere.
 *
 * A name is matched only as a whole word: not followed or preceded by a
 * letter of its own script, so 「ヒナ」 is not found inside 「ヒナタ」. If the
 * provider drops a mark, the answer is used as it came.
 */
object NameMarks {

    class Marked(val text: String, private val marks: Map<String, String>) {
        /** Whether [answer] kept every mark it was sent with. */
        fun keptIn(answer: String): Boolean = marks.keys.all { it in answer }

        val hasMarks: Boolean get() = marks.isNotEmpty()

        fun restore(result: TranslationResult, original: String): TranslationResult {
            if (marks.isEmpty()) return result
            if (result.status != TranslationStatus.Translated) return result.copy(originalText = original)
            var out = result.translatedText
            for ((mark, rendered) in marks) out = out.replace(mark, rendered)
            return result.copy(originalText = original, translatedText = out)
        }
    }

    /** The names of [known] that [line] contains as whole words. */
    fun namesIn(line: String, known: Map<String, String>): Map<String, String> =
        known.filter { (name, rendered) ->
            name.length >= MIN_NAME && rendered.isNotBlank() && '\n' !in rendered && wholeWordIn(line, name)
        }

    fun mark(line: String, known: Map<String, String>): Marked {
        if (known.isEmpty()) return Marked(line, emptyMap())
        var text = line
        val marks = LinkedHashMap<String, String>()
        known.entries.sortedByDescending { it.key.length }.forEachIndexed { i, (name, rendered) ->
            val mark = "{N${i + 1}}"
            val replaced = replaceWhole(text, name, mark)
            if (replaced != text) {
                text = replaced
                marks[mark] = rendered
            }
        }
        return Marked(text, marks)
    }

    private fun wholeWordIn(line: String, name: String): Boolean = replaceWhole(line, name, "\u0000") != line

    private fun replaceWhole(line: String, name: String, by: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < line.length) {
            val at = line.indexOf(name, i)
            if (at < 0) {
                out.append(line, i, line.length)
                break
            }
            val before = line.getOrNull(at - 1)
            val after = line.getOrNull(at + name.length)
            val whole = !sameScript(before, name.first()) && !sameScript(after, name.last())
            out.append(line, i, at).append(if (whole) by else name)
            i = at + name.length
        }
        return out.toString()
    }

    private fun sameScript(c: Char?, of: Char): Boolean {
        if (c == null || !c.isLetter()) return false
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.of(of)
    }

    private const val MIN_NAME = 2
}
