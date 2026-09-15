package com.babel.domain.vision

/**
 * Repairs the punctuation a recogniser invents, so a translator is given
 * something a person might have written.
 *
 * Manga lettering is full of ellipses, and OCR does not read them as ellipses.
 * `……` comes back as `......`, sometimes as `..`, `・・・`, or a stray `:`.
 * Measured consequence: ML Kit translates `......あ` to `......` — the dots
 * survive and the word does not, so a bubble that was recognised perfectly
 * renders as punctuation.
 *
 * This is deliberately about the recogniser's output and not about text in
 * general — accessibility text is what the app actually wrote and needs no
 * repair (`docs/systems/text-model.md`).
 */
object OcrPunctuation {

    /**
     * Characters that are an ellipsis only in company.
     *
     * One of these is ordinary punctuation — `。` ends a sentence, `.` ends an
     * abbreviation — so a lone one is left exactly as it is. Two or more in a
     * row is not punctuation anybody writes, it is a misread ellipsis.
     */
    private const val PIECES = ".．。｡・･:："

    /** Characters that are already an ellipsis on their own. */
    private const val WHOLE = "‥…"

    /** What a repaired ellipsis becomes. */
    const val ELLIPSIS = "…"

    /**
     * Runs of ellipsis fragments collapsed into one ellipsis.
     *
     * A lone trailing `:` is included: at the end of a Japanese line it is not
     * a colon, it is a misread `…` — `そっちは私の使用済み:` is the measured case.
     */
    fun normalize(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (!text[index].isEllipsisish()) {
                builder.append(text[index])
                index++
                continue
            }

            var end = index
            while (end < text.length && text[end].isEllipsisish()) end++
            val run = text.substring(index, end)

            builder.append(if (run.isEllipsis(atEndOfLine = end == text.length)) ELLIPSIS else run)
            index = end
        }
        return builder.toString()
    }

    private fun Char.isEllipsisish(): Boolean = this in PIECES || this in WHOLE

    private fun String.isEllipsis(atEndOfLine: Boolean): Boolean = when {
        length > 1 -> true
        any { it in WHOLE } -> true
        atEndOfLine && (this == ":" || this == "：") -> true
        else -> false
    }

    /**
     * Splits punctuation off the front and back, leaving the sentence itself.
     *
     * A provider given `…あ` has to decide what to do with the ellipsis before
     * it can translate one character; given `あ` it has one job. The affixes are
     * put back by [Affixed.reattach], so nothing is lost from what the reader
     * sees.
     */
    fun split(text: String): Affixed {
        val core = text.trim { it.isEllipsisish() || it.isWhitespace() }
        if (core.isEmpty()) return Affixed("", text, "")

        val start = text.indexOf(core)
        return Affixed(
            prefix = text.take(start),
            core = core,
            suffix = text.substring(start + core.length),
        )
    }

    data class Affixed(val prefix: String, val core: String, val suffix: String) {
        fun reattach(translated: String): String = prefix + translated + suffix
    }

    /**
     * The line cut at its ellipses, so each fragment can be translated as the
     * short sentence it actually is.
     *
     * Measured reason for going further than [split]: taking punctuation off
     * the ends fixes `……あ`, but a line whose ellipses are in the *middle* is
     * still one long string to the provider, and it makes a mess of it —
     * `目を…閉じて` came back as `......关闭`, having lost the eyes. Cut at the
     * ellipsis, the two halves are ordinary phrases.
     *
     * Blank fragments are kept, including the empty ones a leading or trailing
     * ellipsis produces, so [rejoin] puts the line back punctuated as it was.
     *
     * Deliberately does **not** [normalize] first. Repairing is the recogniser
     * path's job and is done once, where the text is produced; splitting is the
     * translation layer's job and runs on every line from every source. Folding
     * the repair in here would apply the trailing-colon rule to accessibility
     * text, where a label really does end in a colon.
     */
    fun fragments(text: String): List<String> = text.split(ELLIPSIS)

    /** Puts [fragments] back together with the ellipses between them. */
    fun rejoin(fragments: List<String>): String = fragments.joinToString(ELLIPSIS)
}
