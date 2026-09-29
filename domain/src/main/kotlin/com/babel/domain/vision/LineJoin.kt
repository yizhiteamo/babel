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
 * there belong to a script that writes spaces between words. Han and kana do
 * not, and either side being one of them is enough to join with nothing.
 *
 * ## Korean is not one of them, and used to be
 *
 * Hangul sat in that list by analogy with Han and kana, and the analogy is
 * simply wrong: Korean separates words with spaces (띄어쓰기 is an orthographic
 * rule, not a style). So `선생님` and `줄게` were glued into `선생님줄게` and
 * handed to the translator as one unspaced run, which came back as the personal
 * name 徐世尼 — a title read as somebody's name because the words had been
 * welded together before anyone tried to read them (`docs/milestones/v2.md`).
 *
 * The recogniser was never the problem: it returns Korean already spaced —
 * measured, one of `kr-mag-01`'s own lines is `사주고 싶어`, space included.
 * This was the only place the spaces went missing.
 *
 * The cost of being wrong the other way: a long Korean word broken across lines
 * by balloon width now gains a space it should not have. Measured on
 * `kr-mag-01`, **all five** multi-line Korean regions break at word boundaries,
 * and a stray space inside one word is a smaller loss than two words welded
 * into one.
 *
 * Deliberately not built on [JapaneseScript], which answers a different
 * question. That one asks whether text *is* Japanese, and is careful to leave
 * Han-only text unclaimed because it is shared with Chinese. Here the sharing
 * is the point: Chinese does not write spaces either. Korean, which shares the
 * region but not the habit, is handled the way Latin is.
 */
object LineJoin {

    private val HIRAGANA = '぀'..'ゟ'
    private val KATAKANA = '゠'..'ヿ'
    private val HAN = '一'..'鿿'

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

    /** Hangul is deliberately absent — see the note on Korean above. */
    private fun writesWithoutSpaces(character: Char): Boolean =
        character in HIRAGANA ||
            character in KATAKANA ||
            character in HAN ||
            character in CJK_PUNCTUATION
}
