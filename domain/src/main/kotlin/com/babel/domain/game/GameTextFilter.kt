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
 * Short Latin words are **not** skipped here: an English game is made of
 * them. A game's controls in another script than the game — AUTO and MENU in
 * a Japanese one — are left alone by [GameLayout.controls], which can see the
 * rest of the frame.
 */
object GameTextFilter {

    enum class Verdict { TRANSLATE, NUMBER, ON_ARTWORK, TOO_LARGE, TOO_SHORT, TOO_SMALL }

    /** [frameHeight] is the frame's short side — its height when the game is held sideways. */
    fun judge(line: GameLine, frameHeight: Int): Verdict {
        val text = line.text.trim()
        val letters = text.count { it.isLetter() }
        return when {
            // 155/240, 264,410,355, 17:13 — and Lv.90, x3, 1/8: digits with at
            // most a short Latin tag are counters, not words.
            letters == 0 || (text.any(Char::isDigit) && text.all { it.code < 0x80 } && letters <= MAX_TAG_LETTERS) ->
                Verdict.NUMBER
            // By the type, not the box: a line of a history screen over the
            // dimmed dialogue box behind it came back in a box 106 px tall
            // around 71 px type — over the limit on some frames, under on
            // others — and its translation came and went with it.
            minOf(line.bounds.height, line.typeHeight) > frameHeight * MAX_HEIGHT_FRACTION -> Verdict.TOO_LARGE
            line.bounds.height < frameHeight * MIN_HEIGHT_FRACTION -> Verdict.TOO_SMALL
            line.backdropError > MAX_BACKDROP_ERROR -> Verdict.ON_ARTWORK
            letters < MIN_LETTERS -> Verdict.TOO_SHORT
            else -> Verdict.TRANSLATE
        }
    }

    /** Between the panel text (≤ 10) and artwork (≥ 14) measured in spike 2. */
    const val MAX_BACKDROP_ERROR = 12.0

    /** Logos measured 8–29% of the screen high; body text at most 5.6%. */
    const val MAX_HEIGHT_FRACTION = 0.07

    /**
     * Price tags and badges drawn into a scene measured 23–38 px on a 1440 px
     * frame, dialogue 57, buttons 48 — and Fate/Grand Order's body text
     * 34–40, which a limit of 2.8% dropped whole. Under 2.2% is scenery; the
     * larger tags are drawn on the art, where the backdrop rule takes them.
     */
    const val MIN_HEIGHT_FRACTION = 0.022

    /** "Lv", "HP", "x": the letters a counter carries. */
    const val MAX_TAG_LETTERS = 3

    /** A single kana or kanji read off a texture is more often noise than a word. */
    const val MIN_LETTERS = 2
}
