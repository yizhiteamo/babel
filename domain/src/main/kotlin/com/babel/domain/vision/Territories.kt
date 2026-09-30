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
 * Two rules keep this safe. A territory always contains its own lettering, so
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
     * [territory] with the side facing [other] moved to the middle of the gap
     * between the two pieces of lettering. Across the wider gap when they are
     * apart both ways, and not at all when the lettering itself overlaps —
     * there is no line to draw then, and each keeps its own reach.
     */
    private fun cut(territory: TextBounds, mine: TextBounds, other: TextBounds): TextBounds {
        val gapRight = other.left - mine.right
        val gapLeft = mine.left - other.right
        val gapBelow = other.top - mine.bottom
        val gapAbove = mine.top - other.bottom
        val across = maxOf(gapRight, gapLeft)
        val along = maxOf(gapBelow, gapAbove)
        if (across < 0 && along < 0) return territory

        return if (across >= along) {
            if (gapRight >= 0) territory.copy(right = minOf(territory.right, mine.right + gapRight / 2))
            else territory.copy(left = maxOf(territory.left, mine.left - gapLeft / 2))
        } else {
            if (gapBelow >= 0) territory.copy(bottom = minOf(territory.bottom, mine.bottom + gapBelow / 2))
            else territory.copy(top = maxOf(territory.top, mine.top - gapAbove / 2))
        }
    }

    private fun intersects(a: TextBounds, b: TextBounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}
