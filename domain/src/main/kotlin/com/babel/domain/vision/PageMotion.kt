package com.babel.domain.vision

import kotlin.math.abs

/**
 * Whether the picture agrees with a shift the balloons voted for.
 *
 * `ScrolledBalloons` places a scrolled screen by geometry alone, and geometry
 * is blind to a page that repeats: identical balloons at a fixed pitch vote
 * just as strongly for a shift one period off. Measured on a built page whose
 * balloons repeat in size and position over different artwork
 * (`docs/milestones/v2.md`), 4 of 32 flings were placed wrong that way — once
 * by 4875px — which reuses one balloon's reading for another.
 *
 * The artwork around the balloons does not repeat, so the screens themselves
 * settle it. Each frame is reduced to a [Profile] — a few hundred rows, a few
 * bands across, each the mean brightness there — and a shift is accepted only
 * when sliding one profile over the other by that much is as good a fit as
 * any slide at all. On the same flings this kept every right answer, turned one
 * tie into the right shift, and turned every wrong one into a fresh read.
 *
 * It needs nothing from the app — no scroll events, which a self-drawn reader
 * may never send — only the two screenshots already taken. Where the picture
 * itself repeats exactly, it cannot choose either, and then nothing is reused;
 * slow, never wrong. Where the two frames do not overlap at all it cannot see,
 * and geometry decides alone.
 */
object PageMotion {

    /** One frame, reduced. [cells] is row-major, [bands] values per row. */
    class Profile(val cells: ByteArray, val rows: Int, val bands: Int, val frameHeight: Int) {
        init {
            require(cells.size == rows * bands) { "profile size does not match its shape" }
        }

        internal fun at(row: Int, band: Int) = cells[row * bands + band].toInt() and 0xFF
    }

    /**
     * The offset to use, or null to read the screen afresh.
     *
     * The picture only vetoes or decides; it never guesses. Geometry's answer
     * stands when the picture agrees with it, or when the picture cannot see
     * it and sees nothing better. Otherwise another candidate is taken only if
     * it is the **one** the picture agrees with — a flat or repeating picture
     * agrees with several, says nothing, and the screen is read again.
     *
     * @param geometric what the balloons voted for, null on a tie.
     * @param candidates absolute page offsets with two votes or more.
     * @param previousOffset the absolute offset of the frame [previous] was taken of.
     */
    fun choose(
        previous: Profile,
        current: Profile,
        previousOffset: Int,
        geometric: Int?,
        candidates: List<Int>,
    ): Int? {
        // Profiles of frames of different heights are on different scales —
        // a browser's bar appearing at the end of a page changes the frame —
        // and compared they agree with nothing, which would veto a good shift.
        if (previous.rows != current.rows || previous.bands != current.bands ||
            previous.frameHeight != current.frameHeight
        ) {
            return geometric
        }
        val lo = (current.rows * MARGIN_TOP).toInt()
        val hi = (current.rows * (1 - MARGIN_BOTTOM)).toInt()
        val reach = hi - lo - MIN_ROWS
        if (reach <= 0) return geometric

        var best = Double.MAX_VALUE
        for (k in -reach..reach) difference(previous, current, k, lo, hi)?.let { if (it < best) best = it }
        if (best == Double.MAX_VALUE) return geometric
        val limit = best * FIT_FACTOR + FIT_SLACK

        val rowPx = current.frameHeight.toDouble() / current.rows
        // How well the picture fits this offset, or null when the two frames
        // would not overlap enough to say — scrolling back two screens to a
        // page already read is exactly that.
        fun fit(offset: Int): Double? {
            val k = (offset - previousOffset) / rowPx
            return listOf(kotlin.math.floor(k).toInt(), kotlin.math.ceil(k).toInt())
                .mapNotNull { difference(previous, current, it, lo, hi) }
                .minOrNull()
        }

        val geometricFit = geometric?.let(::fit)
        val others = candidates.filter { it != geometric && (fit(it) ?: Double.MAX_VALUE) <= limit }
        return when {
            geometricFit != null && geometricFit <= limit -> geometric
            others.size == 1 -> others.single()
            // Geometry's answer cannot be seen from here, and nothing that can
            // be seen fits better: it stands. A tie (null) stays a tie.
            geometricFit == null && others.isEmpty() -> geometric
            else -> null
        }
    }

    /**
     * Mean absolute difference with [current]'s row r laid on [previous]'s
     * row r + k, over the rows both have between [lo] and [hi].
     */
    private fun difference(previous: Profile, current: Profile, k: Int, lo: Int, hi: Int): Double? {
        var total = 0L
        var rows = 0
        for (r in lo until hi) {
            val p = r + k
            if (p < lo || p >= hi) continue
            for (b in 0 until current.bands) total += abs(previous.at(p, b) - current.at(r, b))
            rows += 1
        }
        if (rows < MIN_ROWS) return null
        return total.toDouble() / (rows * current.bands)
    }

    /**
     * The status bar and a browser's bottom bar do not move with the page, and
     * a fixed strip that matches itself at shift zero would vote for "no
     * scroll". Left out, top and bottom.
     */
    private const val MARGIN_TOP = 0.05
    private const val MARGIN_BOTTOM = 0.11

    /** Fewer rows in common than this and a fit means nothing. */
    private const val MIN_ROWS = 40

    /**
     * How much worse than the best slide a candidate may fit and still count
     * as agreeing. Set on the measured flings: right answers fitted within
     * this, the period-off ones did not.
     */
    private const val FIT_FACTOR = 1.15
    private const val FIT_SLACK = 0.5
}
