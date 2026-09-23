package com.babel.domain.vision

/**
 * Whether a line of Japanese has finished saying what it is saying.
 *
 * Manga cuts one sentence across several balloons, and translating each half on
 * its own destroys it: `わたしの` / `めを見て` came back as 我的 and
 * 看着我的眼睛, so the first balloon said nothing at all. Joined, DeepL returns
 * 看着我的眼睛 — the whole line (`docs/milestones/v2.md`).
 *
 * But joining cannot be done on adjacency. Two *separate* utterances joined
 * lose one of themselves, measured on the same run: `はい` +
 * `いくらでも使ってください` came back as 请尽管花吧 with the `はい` gone, and
 * `......あ` + `そっちは私の使用済み` lost the `あ`. So the question is not
 * "is the next balloon nearby" but **"can this one stand on its own"**, and
 * only a balloon that cannot gets joined to its neighbour.
 *
 * ## What counts as unfinished
 *
 * Japanese is verb-final and marks its grammar with trailing particles, so the
 * end of a line says whether more is coming. A line ending in a case or linking
 * particle — `の`, `を`, `が`, `に`, `は`, `で`, `と`, `も`, `から`, `まで` — is
 * waiting for what it modifies. A line ending in a full stop, a question or
 * exclamation mark, or a plain interjection is finished and is left alone.
 *
 * Measured against the four cases the experiment covered, it answers all four
 * correctly. It will still be wrong sometimes — `わたしの` really can be a whole
 * answer to a question — and the cost of being wrong is one balloon translated
 * with a neighbour it did not need, which is a smaller loss than the split.
 *
 * Trailing ellipses are ignored before judging: manga sets them everywhere, and
 * `...仕事中、突然視界が...` ends in a comma's worth of pause rather than a stop.
 */
object JapaneseClause {

    /**
     * Set at the end of a line, these say the sentence is still going.
     *
     * Case and linking particles only. The connective forms that also look
     * unfinished — `〜て`, `〜し` — are **not** here, because in dialogue they
     * are usually a whole line: `めを見て` is "look at my eyes", an imperative,
     * and `目を…閉じて…` is a complete instruction. Both appear in the sample,
     * and treating either as a fragment would join two finished lines, which
     * measurement showed loses one of them.
     */
    private val DANGLING = listOf(
        // Two characters first: `から` must win over the `ら` inside it.
        "から", "まで", "けど", "けれど", "ので", "のに", "とか", "って",
        "の", "を", "が", "に", "は", "で", "と", "も", "へ", "や",
        // A trailing comma continues by definition.
        "、", "，", ",",
    )

    /**
     * Pauses rather than stops, so they are stripped before judging.
     *
     * A full stop is deliberately **not** among them: stripping `。` would make
     * `動かないこと。` end in `こと` and read as unfinished.
     */
    private const val TRAILING = "…・．. 　\n\t"

    /**
     * Marks that end a sentence outright. Checked before [DANGLING] so
     * `先生?` is finished even though nothing else about it says so.
     */
    private const val TERMINAL = "。！？!?」』】〉"

    /**
     * True when [text] is waiting for the balloon after it.
     *
     * False for anything blank, and false for anything that is not Japanese:
     * the rule is about kana particles and means nothing without them.
     */
    fun isUnfinished(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (!JapaneseScript.isPresentIn(trimmed)) return false

        val withoutPauses = trimmed.trimEnd { it in TRAILING }
        if (withoutPauses.isEmpty()) return false
        if (withoutPauses.last() in TERMINAL) return false

        return DANGLING.any { withoutPauses.endsWith(it) } &&
            // A line that is *only* a particle is a misread, not a clause.
            withoutPauses.length > 1
    }
}
