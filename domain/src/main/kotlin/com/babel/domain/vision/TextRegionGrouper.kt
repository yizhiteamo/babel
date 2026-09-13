package com.babel.domain.vision

import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation

/**
 * Tuning for [TextRegionGrouper].
 *
 * Both thresholds are **relative to line thickness**, never absolute pixels: the
 * same page captured at a different screen density would otherwise group
 * differently.
 */
data class GroupingConfig(
    /**
     * How far apart two lines may sit, as a multiple of the thicker one.
     *
     * Measured gaps between columns of one bubble were 14–22px against column
     * widths of 36–42px, i.e. roughly 0.4–0.6. The default leaves headroom for
     * looser lettering without reaching across a panel.
     */
    val maxGapRatio: Float = 1.5f,

    /**
     * How much two lines must overlap along their length to count as
     * neighbours, as a fraction of the shorter one.
     *
     * Columns in a bubble are rarely equal length — a short second column is
     * normal — so this stays low. Its job is only to stop lines in different
     * panels from joining when they happen to be side by side.
     */
    val minOverlapRatio: Float = 0.25f,
) {
    init {
        require(maxGapRatio > 0) { "maxGapRatio must be positive" }
        require(minOverlapRatio in 0f..1f) { "minOverlapRatio must be a fraction" }
    }
}

/**
 * Turns loose OCR lines into speech bubbles with a defined reading order.
 *
 * This exists because a recogniser's own grouping cannot be trusted. Measured on
 * ML Kit: one bubble came back as two separate blocks, while another block held
 * two columns concatenated in the wrong order. Both are geometry problems and
 * are fixed here rather than downstream — see `docs/milestones/v2.md`.
 *
 * Orientation is resolved per line, not per page. Comics mix vertical dialogue
 * with horizontal captions, and the two must never be grouped together: what
 * counts as "adjacent" is itself orientation-dependent, so mixing them would
 * corrupt the bubbles, not merely their order.
 */
class TextRegionGrouper(
    private val config: GroupingConfig = GroupingConfig(),
) {

    /**
     * @param forcedOrientation overrides detection entirely. For tests and for
     *   material known to be uniform; leave null to decide per line.
     */
    fun group(
        lines: List<RecognizedLine>,
        forcedOrientation: TextOrientation? = null,
    ): List<TextRegion> {
        val usable = lines.filter { it.text.isNotBlank() && !it.bounds.isEmpty }
        if (usable.isEmpty()) return emptyList()

        val resolved = resolveOrientations(usable, forcedOrientation)

        // Partition first: adjacency means different things in each orientation,
        // so the two must be grouped independently.
        val regions = resolved.entries
            .groupBy({ it.value }, { it.key })
            .flatMap { (orientation, group) -> regionsWithin(group, orientation) }

        return orderPage(regions)
    }

    /**
     * Engine first, shape second, page majority last.
     *
     * The majority fallback matters for single characters: their box is square,
     * so shape says nothing, and a lone "!" should follow the page it sits on
     * rather than default to either orientation arbitrarily.
     */
    private fun resolveOrientations(
        lines: List<RecognizedLine>,
        forced: TextOrientation?,
    ): Map<RecognizedLine, TextOrientation> {
        if (forced != null) return lines.associateWith { forced }

        val decided = lines.associateWith { line ->
            line.orientation ?: TextOrientationDetector.fromBounds(line.bounds)
        }

        val majority = decided.values.filterNotNull()
            .groupingBy { it }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: TextOrientation.HORIZONTAL

        return decided.mapValues { (_, orientation) -> orientation ?: majority }
    }

    private fun regionsWithin(
        lines: List<RecognizedLine>,
        orientation: TextOrientation,
    ): List<TextRegion> =
        partitionByAdjacency(lines, orientation).map { group ->
            TextRegion(
                lines = sortForReading(group, orientation),
                bounds = group.union(),
                orientation = orientation,
            )
        }

    /**
     * Union-find over "these two lines are neighbours", so a chain of columns
     * ends up in one bubble even when only consecutive pairs are close enough.
     */
    private fun partitionByAdjacency(
        lines: List<RecognizedLine>,
        orientation: TextOrientation,
    ): List<List<RecognizedLine>> {
        val parent = IntArray(lines.size) { it }

        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var walk = index
            while (parent[walk] != root) {
                val next = parent[walk]
                parent[walk] = root
                walk = next
            }
            return root
        }

        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (areNeighbours(lines[i].bounds, lines[j].bounds, orientation)) {
                    parent[find(i)] = find(j)
                }
            }
        }

        return lines.indices
            .groupBy { find(it) }
            .values
            .map { indices -> indices.map { lines[it] } }
    }

    /**
     * Neighbours when they are close across the reading axis and overlap along
     * it. Vertical columns sit side by side and share vertical extent;
     * horizontal lines stack and share horizontal extent.
     */
    private fun areNeighbours(
        a: TextBounds,
        b: TextBounds,
        orientation: TextOrientation,
    ): Boolean = when (orientation) {
        TextOrientation.VERTICAL -> {
            val gap = gapBetween(a.left, a.right, b.left, b.right)
            val thickness = maxOf(a.width, b.width)
            val overlap = overlapRatio(a.top, a.bottom, b.top, b.bottom)
            gap <= thickness * config.maxGapRatio && overlap >= config.minOverlapRatio
        }

        TextOrientation.HORIZONTAL -> {
            val gap = gapBetween(a.top, a.bottom, b.top, b.bottom)
            val thickness = maxOf(a.height, b.height)
            val overlap = overlapRatio(a.left, a.right, b.left, b.right)
            gap <= thickness * config.maxGapRatio && overlap >= config.minOverlapRatio
        }
    }

    /** Zero when the ranges touch or overlap. */
    private fun gapBetween(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Int =
        maxOf(0, maxOf(aStart, bStart) - minOf(aEnd, bEnd))

    /** Shared extent as a fraction of the shorter range. */
    private fun overlapRatio(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Float {
        val overlap = minOf(aEnd, bEnd) - maxOf(aStart, bStart)
        if (overlap <= 0) return 0f
        val shorter = minOf(aEnd - aStart, bEnd - bStart)
        if (shorter <= 0) return 0f
        return overlap.toFloat() / shorter
    }

    /**
     * Japanese columns read right to left, which is the correction the
     * recogniser needs: it emits them left to right, so a bubble comes back
     * reversed.
     */
    private fun sortForReading(
        lines: List<RecognizedLine>,
        orientation: TextOrientation,
    ): List<RecognizedLine> = when (orientation) {
        TextOrientation.VERTICAL ->
            lines.sortedWith(
                compareByDescending<RecognizedLine> { it.bounds.right }.thenBy { it.bounds.top },
            )

        TextOrientation.HORIZONTAL ->
            lines.sortedWith(
                compareBy<RecognizedLine> { it.bounds.top }.thenBy { it.bounds.left },
            )
    }

    /**
     * Orders regions by rows of panels, then across each row.
     *
     * Sorting by top then by right does not work: bubbles sharing a row are not
     * aligned to the pixel. In the measured sample the top-right bubble starts
     * at y=122 and the top-left at y=101, so a plain top-first sort puts the
     * left one first and reverses the page. Rows are therefore built from
     * vertical overlap first.
     *
     * Note this cannot be a `Comparator`: "shares a row" is not transitive — A
     * may overlap B and B overlap C while A and C do not — and a comparator
     * built on it gives unstable results or throws.
     *
     * Which way a row reads follows the page's dominant orientation, not each
     * region's: a horizontal caption on a Japanese page is still encountered in
     * right-to-left page order.
     */
    private fun orderPage(regions: List<TextRegion>): List<TextRegion> {
        val dominant = regions
            .groupingBy { it.orientation }
            .eachCount()
            .maxByOrNull { it.value }
            ?.key
            ?: TextOrientation.HORIZONTAL

        return intoRows(regions).flatMap { row ->
            when (dominant) {
                TextOrientation.VERTICAL -> row.sortedByDescending { it.bounds.right }
                TextOrientation.HORIZONTAL -> row.sortedBy { it.bounds.left }
            }
        }
    }

    /** Regions whose vertical extents overlap belong to the same row of panels. */
    private fun intoRows(regions: List<TextRegion>): List<List<TextRegion>> {
        val rows = mutableListOf<MutableList<TextRegion>>()
        for (region in regions.sortedBy { it.bounds.top }) {
            val current = rows.lastOrNull()
            val sharesRow = current?.any {
                overlapRatio(
                    it.bounds.top,
                    it.bounds.bottom,
                    region.bounds.top,
                    region.bounds.bottom,
                ) > 0f
            } == true

            if (sharesRow) current.add(region) else rows.add(mutableListOf(region))
        }
        return rows
    }

    private fun List<RecognizedLine>.union(): TextBounds = TextBounds(
        left = minOf { it.bounds.left },
        top = minOf { it.bounds.top },
        right = maxOf { it.bounds.right },
        bottom = maxOf { it.bounds.bottom },
        space = first().bounds.space,
    )
}
