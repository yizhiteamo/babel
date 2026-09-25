package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * Balloons the viewport has cut in half, which are not worth reading yet.
 *
 * Scrolling a webtoon puts part of a balloon on screen and the rest off it.
 * Nothing used to notice: the detector clamps every box to the frame, so half a
 * balloon was read, translated and drawn like any other. That is four separate
 * losses at once —
 *
 * - an encoder and decoder pass spent on half a sentence
 * - a paid provider call for it, on the remote route
 * - half a sentence drawn on the page
 * - and a balloon `ScrolledBalloons` cannot match on the next screen, because a
 *   cut box is a different height from the whole one, so it is read again
 *
 * The last one was visible before this existed: the two balloons the scroll
 * measurement failed to match stayed unmatched even at ±32px, and detector
 * jitter is a 4px effect.
 *
 * A cut at the **top** is the worst of them. That balloon was read whole on the
 * previous screen, so the fragment is not new information — and because the
 * fragment reads as different text it gets a different id, which takes the
 * correct translation off the screen and puts a half one in its place.
 *
 * ## Measured
 *
 * `ClippedBalloonMeasurementTest`, on a real webtoon through a scrolling
 * viewport and on eight ordinary pages read whole:
 *
 * | | |
 * |---|---|
 * | Webtoon, touching an edge | 9 of 52 detections |
 * | …of the bottom ones that could be followed | 2 of 3 came back taller — cut |
 * | Whole pages, touching an edge | **0 of 42** |
 *
 * So the rule costs nothing at all on ordinary pages, and on a webtoon it
 * removes about one detection in six.
 *
 * **The balloon outline does not help.** The detector reports one alongside the
 * lettering, and an outline wholly on screen would have proved the words inside
 * it were whole whatever the lettering box did. Measured: of the nine
 * edge-touching boxes, every one either had its outline cut too or had no
 * outline at all. Not one case where the outline would have saved a re-read.
 */
object ClippedBalloons {

    /**
     * Whether more of this balloon would appear if the reader scrolled on.
     *
     * Deliberately not asked for a box touching **both** edges: that balloon is
     * taller than the viewport, so waiting would never make it whole, and
     * skipping it would mean never reading it at all. Measured as nothing on
     * the sample material, and kept because the cost of being wrong about it is
     * a balloon that is silently never translated.
     *
     * @param frameHeight the **content area**, not the display. The frame is
     *   already cropped to what the app is showing, so the edge being tested is
     *   the edge of the reading window rather than of the screen.
     */
    fun waitsForMore(box: TextBounds, frameHeight: Int): Boolean {
        val atTop = box.top <= EDGE
        val atBottom = box.bottom >= frameHeight - EDGE
        if (atTop && atBottom) return false
        return atTop || atBottom
    }

    /** Within this of an edge is touching it, allowing for the box jitter. */
    private const val EDGE = 4
}
