package com.babel.domain.vision

/**
 * A reading that is a texture, not words.
 *
 * The comic recogniser never declines: given a panel's dotted border or a
 * screen-tone strip it answers with one character over and over. Measured on a
 * pixiv page: a row and a column of 「啊」 drawn along a panel's edge, from
 * readings like ああああああ…. No line of speech is a single character
 * repeated dozens of times, nor a handful of characters cycling; a shout
 * (ああああ!) is short, which [MIN_LENGTH] leaves alone.
 */
object PatternReading {

    const val MIN_LENGTH = 12

    /** Distinct letters per letter, at or under which a long reading is a pattern. */
    const val MAX_VARIETY = 0.2

    fun isPattern(text: String): Boolean {
        val letters = text.filter { it.isLetterOrDigit() }
        if (letters.length < MIN_LENGTH) return false
        return letters.toSet().size.toDouble() / letters.length <= MAX_VARIETY
    }
}
