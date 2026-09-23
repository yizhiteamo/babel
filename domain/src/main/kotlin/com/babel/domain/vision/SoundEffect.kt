package com.babel.domain.vision

/**
 * Whether lettering drawn onto the artwork is a sound effect rather than speech.
 *
 * The detector's third class, `text_free`, is text with no balloon around it.
 * It was ignored outright for a while, on the assumption that it was mostly
 * sound effects and that translating `ガチャ` into 咚 under an opaque box costs
 * more than it gives. Counting it on the eight sample pages showed the opposite:
 * **seven of nine were real text** — a page laid out entirely without balloons,
 * and a character sheet whose title and profile block are its only content
 * (`docs/milestones/v2.md`). Dropping the class dropped those pages whole.
 *
 * So the class is read, and the two sound effects are separated here instead.
 *
 * ## Why script and not size
 *
 * Size does not separate them: the smallest real box measured 148x218 and the
 * larger sound effect 105x241, which overlap. Script does. Every sound effect in
 * the sample was **katakana and nothing else**; every real one carried kanji or
 * hiragana — `私はたった今から`, `データを捨てる！`, `聖女見習いちゃん`. That
 * holds beyond the sample, because a Japanese sentence needs its particles and
 * its verb endings, and those are hiragana.
 *
 * Hiragana deliberately does **not** count, though sound effects are written in
 * it too. `はい`, `うん`, `そう` are dialogue, and losing them would cost far
 * more than a missed `どどど`.
 *
 * ## Why a length bound as well
 *
 * Katakana alone would also silence a long loanword line, which is speech. The
 * measured gap is wide — the sound effects ran 3 and 6 characters, the real text
 * 8 and up — so the bound sits at 8 and only ever applies to text that is
 * already katakana-only.
 *
 * ## Where it applies
 *
 * Free text only. Short katakana **inside** a balloon (`データ`) is somebody
 * speaking, and the balloon is the evidence for that.
 */
object SoundEffect {

    private val KATAKANA = '゠'..'ヿ'

    /**
     * Marks that carry no script of their own.
     *
     * Stripped before judging, so `ガチャッ！` is measured as `ガチャッ` rather
     * than being let through by its punctuation.
     */
    private const val MARKS = "！!？?…‥・。、．，.,〜~ー-―—‐ 　\n\t"

    /** Past this, katakana-only text is a loanword line rather than a noise. */
    private const val MAX_LENGTH = 8

    /**
     * True when [text] reads as a drawn noise rather than something said.
     *
     * Blank text is not a sound effect: an empty recognition is a failed read,
     * and the caller already drops those for its own reasons.
     */
    fun isDrawnNoise(text: String): Boolean {
        val stripped = text.filterNot { it in MARKS }
        if (stripped.isEmpty()) return false
        if (stripped.length > MAX_LENGTH) return false
        return stripped.all { it in KATAKANA }
    }
}
