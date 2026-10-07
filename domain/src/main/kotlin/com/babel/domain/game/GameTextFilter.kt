package com.babel.domain.game

/**
 * Which recognised lines in a game are worth translating.
 *
 * General rules, not tuned to a title: what decides is what every game shares —
 * whether a line is a number, whether it sits on a plain panel, how big it is.
 * Measured on five screenshots of one game (`docs/milestones/v3.md`, spike 2):
 * with these rules every line landed in the right group — dialogue, choices,
 * pop-ups and plain labels kept; logos, stylised lettering, labels drawn on
 * icons and numbers left alone. Five screens prove the signal, not the
 * thresholds; they are to be checked against other games.
 *
 * Short Latin words are **not** skipped: AUTO and MENU are interface labels a
 * player may well want translated, and an English game is made of them.
 */
object GameTextFilter {

    enum class Verdict { TRANSLATE, NUMBER, ON_ARTWORK, TOO_LARGE, TOO_SHORT }

    fun judge(line: GameLine, frameHeight: Int): Verdict {
        val text = line.text.trim()
        val letters = text.count { it.isLetter() }
        return when {
            // 155/240, 264,410,355, 17:13 — and Lv.90, x3, 1/8: digits with at
            // most a short Latin tag are counters, not words.
            letters == 0 || (text.any(Char::isDigit) && text.all { it.code < 0x80 } && letters <= MAX_TAG_LETTERS) ->
                Verdict.NUMBER
            line.bounds.height > frameHeight * MAX_HEIGHT_FRACTION -> Verdict.TOO_LARGE
            line.backdropError > MAX_BACKDROP_ERROR -> Verdict.ON_ARTWORK
            letters < MIN_LETTERS -> Verdict.TOO_SHORT
            else -> Verdict.TRANSLATE
        }
    }

    /** Between the panel text (≤ 10) and artwork (≥ 14) measured in spike 2. */
    const val MAX_BACKDROP_ERROR = 12.0

    /** Logos measured 8–29% of the screen high; body text at most 5.6%. */
    const val MAX_HEIGHT_FRACTION = 0.07

    /** "Lv", "HP", "x": the letters a counter carries. */
    const val MAX_TAG_LETTERS = 3

    /** A single kana or kanji read off a texture is more often noise than a word. */
    const val MIN_LETTERS = 2
}
