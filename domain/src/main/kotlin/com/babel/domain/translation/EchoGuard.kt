package com.babel.domain.translation

/**
 * Whether a translation is really another line's translation, handed back.
 *
 * With the page as context, a model can answer the wrong line. Measured on a
 * pixiv character sheet, four description blocks that begin alike: with
 * context, three came back as paraphrases of the one already rendered (character
 * bigram overlap 0.42–0.91) or as a fourth's (0.63); without context all four
 * were right, and genuine translations on the page overlapped each other by at
 * most 0.19. The threshold sits in that gap.
 */
object EchoGuard {

    const val THRESHOLD = 0.35

    fun repeatsAnother(translation: String, others: List<String>): Boolean {
        val mine = bigrams(translation)
        if (mine.size < MIN_BIGRAMS) return false
        return others.any { similarity(mine, bigrams(it)) >= THRESHOLD }
    }

    /** Dice coefficient over character bigrams, ignoring spacing and punctuation. */
    internal fun similarity(a: Map<String, Int>, b: Map<String, Int>): Double {
        val total = a.values.sum() + b.values.sum()
        if (total == 0) return 0.0
        val shared = a.entries.sumOf { (k, n) -> minOf(n, b[k] ?: 0) }
        return 2.0 * shared / total
    }

    internal fun bigrams(text: String): Map<String, Int> {
        val letters = text.filter { it.isLetterOrDigit() }
        return letters.windowed(2).groupingBy { it }.eachCount()
    }

    /** A short answer repeating a name is consistency, not an echo. */
    private const val MIN_BIGRAMS = 8
}
