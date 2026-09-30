package com.babel.domain.vision

import com.babel.core.model.TextBounds
import kotlin.math.abs

/**
 * Where the page is, worked out from the accessibility tree.
 *
 * The largest container used to be the whole answer, and on a page that is one
 * image it still is. But a webtoon site serves a chapter as a column of images,
 * and there the largest container is only whichever image shows the most of
 * itself. Measured in Edge on twelve stacked pages (`docs/milestones/v2.md`):
 *
 * - about 62 untranslated balloons on sixteen settled screens, every one of
 *   them on an image beside the chosen one
 * - translations already on screen taken down as the choice moved to the next
 *   image, though their balloons had not moved
 * - a balloon touching an image's lower edge never read, because that edge was
 *   taken for the viewport's
 * - scroll reuse failing 9 of 15 flings, the last screen's balloons all having
 *   landed outside the new crop
 *
 * So the column is followed: starting from the largest container, every node
 * that sits in the same column and carries it further up or down is part of
 * the page, whatever its size. The image only partly on screen is exactly such
 * a node — measured, a full-width box a few hundred pixels tall overlapping its
 * neighbour by 3–4px — and being small it used to be counted as an interface
 * label, which also excluded the balloons on it.
 *
 * Nothing changes when there is one container: the answer is that container,
 * as before.
 */
object ContentArea {

    data class Result(
        val area: TextBounds,
        /** Every node the area was built from, the largest container included. */
        val members: List<TextBounds>,
    )

    /**
     * @param containers nodes too large to be a label.
     * @param labels everything else the tree exposed.
     * @return null when there is no container to start from.
     */
    fun of(containers: List<TextBounds>, labels: List<TextBounds>): Result? {
        val seed = containers.maxByOrNull { it.width.toLong() * it.height } ?: return null

        val members = mutableListOf(seed)
        var top = seed.top
        var bottom = seed.bottom
        val pending = (containers - seed + labels).filter { sameColumn(it, seed) }.toMutableList()

        // Grown one neighbour at a time, so a gap anywhere in the column stops
        // it there rather than being bridged by a node further on.
        while (true) {
            val next = pending.firstOrNull { it.top >= bottom - GAP && it.top <= bottom + GAP }
                ?: pending.firstOrNull { it.bottom <= top + GAP && it.bottom >= top - GAP }
                ?: break
            pending -= next
            members += next
            top = minOf(top, next.top)
            bottom = maxOf(bottom, next.bottom)
        }

        return Result(seed.copy(top = top, bottom = bottom), members)
    }

    /**
     * Both edges aligned with the seed. A paragraph, a caption or a sidebar is
     * narrower than the page it sits on; the images of one column are not.
     */
    private fun sameColumn(node: TextBounds, seed: TextBounds): Boolean =
        abs(node.left - seed.left) <= EDGE && abs(node.right - seed.right) <= EDGE

    /** Measured overlap between neighbouring images is 3–4px. */
    private const val EDGE = 16
    private const val GAP = 24
}
