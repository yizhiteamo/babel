package com.babel.domain.vision

/**
 * Puts a region's recognised lines back into one string.
 *
 * A recogniser returns lines, and what belongs between them depends on the
 * script. Running them together with nothing is right for a vertical Japanese
 * balloon — its columns continue one sentence and a space would be wrong — and
 * it was what the pipeline did to **every** region. On horizontal Latin text,
 * where lines break at word boundaries, it glues words together.
 *
 * Measured on `jap-mag-08`, an English page. Every seam in what the translator
 * was given is a line boundary:
 *
 * ```
 * A succubus who | can't read a room | to save her life | you say hi and she
 *   -> whocan't ... roomto ... lifeyou ... shestarts
 * ```
 *
 * Three balloons, eighteen lines, and nearly every corrupted word in them was
 * made here rather than by the recogniser — which is what had a chat model
 * leaving `apparently` sitting untranslated in the middle of a Chinese sentence
 * (`docs/milestones/v2.md`). The translator was guessing at words that were
 * never on the page.
 *
 * ## Why the test is on the script and not on the orientation
 *
 * Orientation looks like the obvious discriminator and is the wrong one: page
 * 06's profile block is **horizontal Japanese**, and a space between its lines
 * would be as wrong as one inside a vertical column.
 *
 * So the question is asked of the seam itself — do the two characters meeting
 * there belong to a script that writes spaces between words. Han, kana and
 * Hangul do not, and either side being one of them is enough to join with
 * nothing.
 *
 * Deliberately not built on [JapaneseScript], which answers a different
 * question. That one asks whether text *is* Japanese, and is careful to leave
 * Han-only text unclaimed because it is shared with Chinese. Here the sharing
 * is the point: Chinese does not write spaces either.
 */
object LineJoin {

    private val HIRAGANA = '぀'..'ゟ'
    private val KATAKANA = '゠'..'ヿ'
    private val HAN = '一'..'鿿'
    private val HANGUL = '가'..'힣'

    /** Punctuation set in CJK text, which takes no space around it either. */
    private const val CJK_PUNCTUATION = "、。〈〉《》「」『』【】〔〕・！？：；，．－～"

    /**
     * [lines] joined, with a space only where two space-writing scripts meet.
     *
     * Blank lines are dropped: a recogniser that returns one contributes
     * nothing but would otherwise leave a double space behind.
     */
    fun join(lines: List<String>): String {
        val usable = lines.filter { it.isNotBlank() }
        if (usable.isEmpty()) return ""

        return buildString {
            append(usable.first())
            for (line in usable.drop(1)) {
                if (needsSpaceBetween(last(), line.first())) append(' ')
                append(line)
            }
        }
    }

    private fun needsSpaceBetween(before: Char, after: Char): Boolean {
        if (before.isWhitespace() || after.isWhitespace()) return false
        return !writesWithoutSpaces(before) && !writesWithoutSpaces(after)
    }

    private fun writesWithoutSpaces(character: Char): Boolean =
        character in HIRAGANA ||
            character in KATAKANA ||
            character in HAN ||
            character in HANGUL ||
            character in CJK_PUNCTUATION
}
