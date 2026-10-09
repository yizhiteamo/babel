package com.babel.domain.game

import kotlin.math.abs

/**
 * The colours sampled at the two ends of each row of a line, cleaned of rows
 * where one end landed on something that is not the panel.
 *
 * Measured: a 「――」 the recogniser split off the start of a line left its
 * tail where the next piece's left edge was sampled, the rows it crossed came
 * out white at that end, and the patch drew a white line fading through the
 * translation. A row whose one end stands far from that end's usual colour,
 * while the other end does not, has a stray sample; it takes the colour of the
 * nearest ordinary row on that side. A rule across the whole width — the line
 * under a speaker's name — is light at **both** ends and is kept.
 *
 * Packed ARGB in and out, as `RowGradient` holds them.
 */
object BackdropEdges {

    /** Brightness (0–255) a sample may stand from its side's middle value. */
    const val OUTLIER_LUMA = 40.0

    fun repaired(left: List<Int>, right: List<Int>): Pair<List<Int>, List<Int>> {
        require(left.size == right.size)
        if (left.size < 3) return left to right
        val l = left.map(::luma)
        val r = right.map(::luma)
        val lMid = l.sorted()[l.size / 2]
        val rMid = r.sorted()[r.size / 2]
        val lOdd = l.map { abs(it - lMid) > OUTLIER_LUMA }
        val rOdd = r.map { abs(it - rMid) > OUTLIER_LUMA }
        fun fix(colours: List<Int>, odd: List<Boolean>, otherOdd: List<Boolean>): List<Int> =
            colours.indices.map { i ->
                if (!odd[i] || otherOdd[i]) {
                    colours[i]
                } else {
                    nearestOrdinary(colours, odd, i) ?: colours[i]
                }
            }
        return fix(left, lOdd, rOdd) to fix(right, rOdd, lOdd)
    }

    private fun nearestOrdinary(colours: List<Int>, odd: List<Boolean>, at: Int): Int? {
        for (d in 1 until colours.size) {
            if (at - d >= 0 && !odd[at - d]) return colours[at - d]
            if (at + d < colours.size && !odd[at + d]) return colours[at + d]
        }
        return null
    }

    private fun luma(argb: Int): Double {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000.0
    }
}
