package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * How far each translation on a page may grow, so that neighbours do not
 * cover one another.
 *
 * Each translation grows out from its lettering until the background stops
 * (`BubbleBounds`), bounded by its balloon — and each did so alone. Two
 * balloons drawn touching, or one balloon the detector reports as two
 * overlapping outlines, then gave two boxes that lie over each other. Measured
 * on fourteen screens (`docs/milestones/v2.md`): 18 overlapping pairs out of
 * 144 translations, 12 of them in intersecting outlines and 6 on lettering
 * with no outline at all, the worst covering 15% of the neighbour's text.
 *
 * Everything needed to prevent it is known before anything is read: the
 * detector reports every lettering box and every outline at once. So each
 * piece of lettering is given a territory up front — its own reach, cut back
 * halfway across the gap to any neighbour whose reach it shares — and grows
 * only within it.
 *
 * Two rules keep this safe. A territory holds its own lettering — all of it,
 * unless two lettering boxes overlap, and then all but half the overlap — so
 * a translation still covers the text it replaces. And each territory is cut
 * from the original boxes only, never from another territory, so the answer
 * does not depend on the order the page is read in.
 */
object Territories {

    /**
     * @param text the lettering.
     * @param reach the furthest its translation could grow: the balloon, or for
     *   lettering on the art, a generous box around it ([openReach]).
     */
    data class Item(val text: TextBounds, val reach: TextBounds)

    fun of(items: List<Item>): List<TextBounds> = items.mapIndexed { index, me ->
        items.foldIndexed(me.reach) { otherIndex, territory, other ->
            if (otherIndex == index || !intersects(me.reach, other.reach)) territory
            else cut(territory, me.text, other.text)
        }
    }

    /**
     * Where open lettering could reach. `BubbleBounds` caps its growth at twice
     * its own area, so its own size again on every side is more than it can
     * ever take — a superset is all this needs to be.
     */
    fun openReach(text: TextBounds, frame: TextBounds): TextBounds = text.copy(
        left = maxOf(frame.left, text.left - text.width),
        top = maxOf(frame.top, text.top - text.height),
        right = minOf(frame.right, text.right + text.width),
        bottom = minOf(frame.bottom, text.bottom + text.height),
    )

    /**
     * [territory] with the side facing [other] moved to the middle between the
     * two pieces of lettering, across whichever axis they are further apart on.
     *
     * Usually that is the middle of a gap. When the lettering boxes themselves
     * overlap — two columns of one balloon set diagonally, measured 4 to 17px
     * deep on six of seven such pairs and 54px on the seventh — it is the
     * middle of the overlap, so each gives up half of it. Leaving those alone
     * was the first version, and a user saw the result: one translation drawn
     * over the corner of the next. A lettering box carries padding round its
     * glyphs, and half of a strip that thin is padding, not text.
     *
     * Not when one box covers more than half of the other along the axis that
     * would be cut: that is the same lettering reported twice, and a line
     * through the middle of it would leave neither copy its own words.
     */
    private fun cut(territory: TextBounds, mine: TextBounds, other: TextBounds): TextBounds {
        val across = maxOf(other.left - mine.right, mine.left - other.right)
        val along = maxOf(other.top - mine.bottom, mine.top - other.bottom)
        val horizontal = across >= along
        val apart = if (horizontal) across else along
        if (apart < 0) {
            val shorter = if (horizontal) minOf(mine.width, other.width) else minOf(mine.height, other.height)
            if (-apart * 2 > shorter) return territory
        }

        return if (horizontal) {
            if (other.left + other.right >= mine.left + mine.right) {
                territory.copy(right = minOf(territory.right, (mine.right + other.left) / 2))
            } else {
                territory.copy(left = maxOf(territory.left, (mine.left + other.right) / 2))
            }
        } else {
            if (other.top + other.bottom >= mine.top + mine.bottom) {
                territory.copy(bottom = minOf(territory.bottom, (mine.bottom + other.top) / 2))
            } else {
                territory.copy(top = maxOf(territory.top, (mine.top + other.bottom) / 2))
            }
        }
    }

    private fun intersects(a: TextBounds, b: TextBounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
