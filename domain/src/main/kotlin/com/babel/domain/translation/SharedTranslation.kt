package com.babel.domain.translation

/**
 * Divides one translation among the balloons it was translated for.
 *
 * When a sentence is split across balloons, the fix is to translate the whole
 * thing at once ([JapaneseClause] decides which balloons belong together). That
 * leaves one string and several boxes, and **no correspondence between them**:
 * `わたしの` / `めを見て` becomes 看着我的眼睛, which orders the words
 * *[look-at][my][eyes]* where the Japanese ordered them *[my][eyes look-at]*.
 * Cutting the translation where the source was cut gives 看着我 / 的眼睛.
 *
 * So the balloons are not treated as corresponding to source fragments. They
 * are treated as **containers**, and the translation is flowed through them in
 * reading order — filling each, breaking where it fills up, the way a
 * typesetter fills columns. 看着我的 / 眼睛 reads correctly to somebody taking
 * the balloons in order, which is how the page is read anyway.
 *
 * Weights are the boxes' areas, so a large balloon takes more of the line than
 * a small one. Breaks prefer punctuation when there is any near the proportional
 * point, because a break the author already put there reads better than one
 * arithmetic chose.
 */
object SharedTranslation {

    /**
     * How far from the proportional cut a punctuation break may be taken,
     * as a fraction of the whole text.
     *
     * Wide enough to find the comma in a two-clause line, narrow enough that a
     * small balloon cannot swallow a large one's share.
     */
    private const val SNAP_WINDOW = 0.2

    /** Breaking after one of these leaves a line that reads as finished. */
    private const val BREAK_AFTER = "，,。.！!？?、…»）)】」』;；:："

    /**
     * [text] divided into one part per weight, in order.
     *
     * Always returns exactly `weights.size` parts. A part can be empty only
     * when there is less text than there are boxes, which means a balloon is
     * covered and blank — still better than leaving the original showing under
     * a sentence that has moved to its neighbour.
     */
    fun divide(text: String, weights: List<Int>): List<String> {
        if (weights.isEmpty()) return emptyList()
        if (weights.size == 1) return listOf(text)
        if (text.isEmpty()) return List(weights.size) { "" }

        val cuts = cutPoints(text, weights)
        return buildList {
            var start = 0
            for (cut in cuts) {
                add(text.substring(start, cut))
                start = cut
            }
            add(text.substring(start))
        }
    }

    /** One part of a division, without computing the others. */
    fun partOf(text: String, weights: List<Int>, index: Int): String =
        divide(text, weights).getOrElse(index) { "" }

    /**
     * Where to cut, in ascending order: `weights.size - 1` positions.
     *
     * Each is placed proportionally and then snapped to the nearest punctuation
     * inside the window, never crossing a neighbouring cut and never leaving a
     * box with nothing while text remains.
     */
    private fun cutPoints(text: String, weights: List<Int>): List<Int> {
        val total = weights.sumOf { it.coerceAtLeast(1).toLong() }
        val window = (text.length * SNAP_WINDOW).toInt().coerceAtLeast(1)

        val cuts = mutableListOf<Int>()
        var carried = 0L
        var previous = 0

        for (index in 0 until weights.size - 1) {
            carried += weights[index].coerceAtLeast(1).toLong()
            val proportional = ((text.length * carried) / total).toInt()

            // Never behind the last cut, and always leaving one character for
            // each box still to come — as far as there is text to give.
            val remaining = weights.size - index - 1
            val lowest = (previous + 1).coerceAtMost(text.length)
            val highest = (text.length - remaining).coerceAtLeast(lowest)

            val target = proportional.coerceIn(lowest, highest)
            val cut = snapToPunctuation(text, target, window, lowest, highest)
            cuts += cut
            previous = cut
        }
        return cuts
    }

    /**
     * [target] moved to just after the closest punctuation within [window],
     * or left where it is when there is none.
     */
    private fun snapToPunctuation(
        text: String,
        target: Int,
        window: Int,
        lowest: Int,
        highest: Int,
    ): Int {
        for (distance in 0..window) {
            for (candidate in intArrayOf(target - distance, target + distance)) {
                if (candidate < lowest || candidate > highest) continue
                // A cut at `candidate` puts `text[candidate - 1]` at the end of
                // the part before it, so that is the character to test.
                if (candidate > 0 && text[candidate - 1] in BREAK_AFTER) return candidate
            }
        }
        return target
    }
}
