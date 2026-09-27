package com.babel.domain.vision

/**
 * What language a page of artwork is written in, decided from the page rather
 * than from one balloon.
 *
 * A balloon of nothing but Han characters — 「先生」, 「大丈夫」, a name — is
 * genuinely ambiguous between Japanese and Chinese, and [JapaneseScript] says
 * so and refuses to guess. That refusal is right at the line, and it leaves a
 * hole: with no language stated, such a balloon falls back to identification,
 * identification reads Han as Chinese, and for a reader whose target *is*
 * Chinese the result is source == target and the balloon comes back unchanged.
 * The user sees 「先生」 left untranslated where 「老师」 was wanted.
 *
 * The page does not have the ambiguity the balloon has. Measured over four
 * pages: on Japanese pages **every** balloon (46 of 46) carried kana, and the
 * least of them was 44% kana; on Chinese pages only six of thirty-seven held a
 * kana at all, and the most was 11.8%. One balloon settles the page, and the
 * rest of the page then inherits the answer.
 *
 * ## Why the two verdicts are not symmetric
 *
 * [Verdict.JAPANESE] needs one balloon, because a balloon that is 44% kana
 * cannot be Chinese. [Verdict.NOT_JAPANESE] needs several, because one Han-only
 * balloon proves nothing — a Japanese page can open with one. So the page stays
 * [Verdict.UNDECIDED] until either a Japanese balloon arrives or enough Han has
 * gone by without one.
 *
 * In practice the wait is short in the case that matters: a Japanese page
 * answers on its first balloon, which is the page whose ambiguous balloons are
 * being held.
 *
 * Not thread-safe, and not meant to be: one page is read by one scan.
 */
class PageLanguage {

    enum class Verdict { UNDECIDED, JAPANESE, NOT_JAPANESE }

    private var japaneseSeen = false
    private var balloons = 0
    private var hanSeen = 0

    val verdict: Verdict
        get() = when {
            japaneseSeen -> Verdict.JAPANESE
            balloons >= MIN_BALLOONS && hanSeen >= MIN_HAN -> Verdict.NOT_JAPANESE
            else -> Verdict.UNDECIDED
        }

    /** Adds one balloon's recognised text to the evidence. */
    fun observe(text: String) {
        if (JapaneseScript.isJapanese(text)) {
            japaneseSeen = true
            return
        }
        balloons++
        hanSeen += text.count { JapaneseScript.isHan(it) }
    }

    /** Starts a new page. */
    fun reset() {
        japaneseSeen = false
        balloons = 0
        hanSeen = 0
    }

    private companion object {
        /**
         * Balloons of non-Japanese text before the page is called non-Japanese.
         *
         * Three rather than one because a Japanese page may open with a Han-only
         * balloon, and rather than ten because the whole point is to answer
         * before the page has been read. Measured: Chinese balloons carry
         * 15–24 Han characters each, so three of them clears [MIN_HAN] as well.
         */
        const val MIN_BALLOONS = 3

        /**
         * Han characters that must have gone by as well, so a page opening with
         * three one-word balloons does not settle the question on a dozen
         * characters.
         */
        const val MIN_HAN = 20
    }
}
