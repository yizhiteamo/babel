package com.babel.domain.vision

import com.babel.core.model.TextBounds
import kotlin.math.abs

/**
 * Finds the balloons that were already on the last screen, moved.
 *
 * Scrolling a webtoon re-read every balloon from scratch: 4.3s of recognition
 * and eight provider calls per swipe, measured on a device, for a screen that
 * was mostly the same screen a moment earlier. The translation cache could not
 * help — it is keyed on the text, and a balloon read twice comes back slightly
 * different, so 8 of 10 missed.
 *
 * Caching **recognition** was tried three times and failed three times, all on
 * the same idea: hash what the balloon looks like. The record says why it cannot
 * work — the detector runs on a 640x640 resize of the whole screen, so a
 * scrolled screen is different input and the pixels it hands back are never the
 * same twice. Its closing line named the untried alternative, which is this one:
 * match on **where** the balloon is, not on what it looks like
 * (`docs/milestones/v2.md`).
 *
 * ## It was measured before it was written
 *
 * Five scrolls of a real webtoon, `ScrollMatchMeasurementTest`:
 *
 * - **18 of the 20** balloons that stayed on screen were matched
 * - the shift was **recovered from the boxes alone** every time, so nothing has
 *   to be told how far the screen moved
 * - ±8px is the working tolerance: ±16 and ±32 gained nothing on the measured
 *   page and only widen the chance of pairing two different balloons
 *
 * The memory is not limited to the previous screen. Balloons are kept in
 * **page** coordinates and each frame solves for its own absolute offset
 * against all of them, so scrolling back to something already read costs
 * nothing and no drift accumulates — every frame is measured against the
 * original coordinates rather than against the frame before it.
 *
 * ## What it deliberately does not do
 *
 * No horizontal tracking. A webtoon scrolls vertically, and a page that moved
 * sideways is a different situation — there the votes simply fail to agree and
 * everything is read again, which is where this started.
 */
object ScrolledBalloons {

    /**
     * How far the screen moved between two readings, or null when nothing
     * consistent says.
     *
     * Every plausible pair votes for the shift it implies and the winner takes
     * it. Null is the honest answer for a page that changed rather than moved —
     * a new chapter, a different app — and the caller then reads everything, as
     * it did before any of this.
     */
    fun shiftBetween(before: List<TextBounds>, after: List<TextBounds>): Int? {
        if (before.isEmpty() || after.isEmpty()) return null

        val deltas = mutableListOf<Int>()
        for (old in before) {
            for (new in after) {
                if (sameShape(old, new)) deltas += old.top - new.top
            }
        }
        if (deltas.isEmpty()) return null

        // The delta the most others agree with, where agreement means the same
        // tolerance used everywhere else.
        //
        // Bucketing was the first version and it had two faults. It returned
        // the bucket, so the answer was rounded down by up to 8px — measured as
        // 696 for an actual 700, every time. And when the true delta straddled
        // a bucket edge it split its own votes in half, which matters most
        // exactly when there are fewest of them. Counting neighbours has
        // neither problem and is no more code.
        val best = deltas.maxByOrNull { candidate -> agreeing(deltas, candidate).size }!!
        val winners = agreeing(deltas, best)

        // One agreeing pair is a coincidence; two is a scroll. A single balloon
        // on screen therefore gets read again, which costs one recognition and
        // cannot show the wrong words.
        if (winners.size < MIN_VOTES) return null

        // A second, separate group with just as much support means the boxes do
        // not agree on one answer. Saying so is the honest reply — the caller
        // then reads the screen, which is only slow. Picking one of them puts
        // somebody else's words in a balloon, which is wrong.
        val rival = deltas.filter { abs(it - best) > TOLERANCE }
            .maxOfOrNull { agreeing(deltas, it).size } ?: 0
        if (rival >= winners.size) return null

        // The middle of what the winners actually said. A median rather than a
        // mean because one box landing badly should not drag the answer for the
        // rest of them.
        return winners.sorted()[winners.size / 2]
    }

    private fun agreeing(deltas: List<Int>, candidate: Int) =
        deltas.filter { abs(it - candidate) <= TOLERANCE }

    /**
     * Whether [now] is [then] after the screen moved by [shift].
     *
     * Size is part of the question and not a detail: two balloons of different
     * sizes landing at the same height are not the same balloon, and reusing one
     * for the other would put somebody else's words on screen — a worse failure
     * than the re-read this exists to avoid.
     */
    fun isSame(then: TextBounds, now: TextBounds, shift: Int): Boolean =
        abs(now.left - then.left) <= TOLERANCE &&
            abs((now.top + shift) - then.top) <= TOLERANCE &&
            sameShape(then, now)

    private fun sameShape(a: TextBounds, b: TextBounds): Boolean =
        abs(a.width - b.width) <= TOLERANCE &&
            abs(a.height - b.height) <= TOLERANCE &&
            abs(a.left - b.left) <= TOLERANCE

    private const val TOLERANCE = 8
    private const val MIN_VOTES = 2
}
