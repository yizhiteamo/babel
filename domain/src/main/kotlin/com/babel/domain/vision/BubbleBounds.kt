package com.babel.domain.vision

import com.babel.core.model.TextBounds

/**
 * Finds the space a piece of text sits in — in practice the inside of a speech
 * bubble — by flooding outwards from the text over background-coloured pixels.
 *
 * Recognition returns a box hugging the lettering, and for vertical Japanese
 * that box is a narrow column. A translation confined to it covers only part of
 * what it replaces, so the original shows around the edges and the reader sees
 * two texts at once.
 *
 * **Why flooding rather than growing a rectangle.** The first version grew the
 * box one edge at a time while the whole edge was background. Measured on real
 * pages, bubbles several times wider than their text grew by 9, 15, and 0
 * pixels: any glyph the recogniser missed, or any character just outside the
 * box, sat on the candidate edge and stopped growth for good. A wall, from one
 * letter.
 *
 * A flood treats those letters as holes and flows around them. Only a closed
 * contour — the bubble's own outline — actually stops it.
 *
 * Pure arithmetic over a background test, so it can be exercised against a
 * drawn bubble without a frame. Reading pixels is the platform's job.
 */
object BubbleBounds {

    /**
     * Pixels per step of the flood. Coarse on purpose: the question is where
     * the bubble ends, and stepping every pixel would cost hundreds of
     * thousands of reads per region to answer it no better.
     */
    private const val STEP = 4

    /**
     * Largest share of the frame the flood may cover before the search is
     * abandoned, in fifths.
     *
     * Measured against the frame, not the text. Capping growth at a multiple of
     * the text box rejected every real bubble — a column of vertical Japanese
     * is narrow and its balloon was seven times wider. Nothing that fills most
     * of the screen is a speech bubble.
     */
    private const val MAX_FRAME_NUMERATOR = 4
    private const val MAX_FRAME_DENOMINATOR = 5

    /**
     * Grows [start] to the enclosure around it, or returns it unchanged when
     * there is no enclosure.
     *
     * A speech bubble encloses its text on all sides, so the flood ends by
     * meeting the outline everywhere. Text on a toolbar, a caption strip, or an
     * open white page is not enclosed: the flood runs to the edge of the frame
     * or over the size cap, and that is read as *there was nothing around this*
     * rather than as a limit to stop at. Growing those boxes actively harms —
     * it turned a small overlay over a browser tab title into a large one.
     *
     * @param limit the frame; the flood never leaves it.
     * @param isBackground whether the pixel at (x, y) still looks like the
     *   region's background — the caller decides what that means, having
     *   sampled the colour.
     */
    fun expand(
        start: TextBounds,
        limit: TextBounds,
        isBackground: (Int, Int) -> Boolean,
    ): TextBounds {
        if (start.width <= 0 || start.height <= 0) return start

        val maxCells = maxCells(limit)
        val seeds = seeds(start, isBackground)
        if (seeds.isEmpty()) return start

        val visited = HashSet<Long>(seeds.size * 4)
        val queue = ArrayDeque<Int>(seeds.size * 2)
        seeds.forEach { (x, y) ->
            if (visited.add(key(x, y))) {
                queue.addLast(x)
                queue.addLast(y)
            }
        }

        // Horizontal extent of the flood, per row. An inscribed rectangle can
        // be read straight off this; a bounding box cannot.
        val rows = HashMap<Int, IntArray>()

        while (queue.isNotEmpty()) {
            val x = queue.removeFirst()
            val y = queue.removeFirst()

            val row = rows.getOrPut(y) { intArrayOf(x, x) }
            if (x < row[0]) row[0] = x
            if (x > row[1]) row[1] = x

            // Reaching the frame means the flood was never enclosed, and the
            // size cap means the same thing for a very large open area.
            if (x - STEP < limit.left || x + STEP > limit.right) return start
            if (y - STEP < limit.top || y + STEP > limit.bottom) return start
            if (visited.size > maxCells) return start

            for ((nx, ny) in neighbours(x, y)) {
                if (!visited.add(key(nx, ny))) continue
                if (!isBackground(nx, ny)) continue
                queue.addLast(nx)
                queue.addLast(ny)
            }
        }

        return inscribe(rows, start) ?: start
    }

    /**
     * The largest rectangle that fits **inside** the flooded shape and still
     * covers the text.
     *
     * A bounding box will not do. The overlay is painted as an opaque
     * rectangle, and a balloon is round: the bounding box's corners fall
     * outside the outline, so painting it erases the drawn line and a bite of
     * the art around it. A box one step small costs nothing; one that crosses
     * the line damages the page.
     *
     * Read off the flood's per-row extents: across any set of rows, the widest
     * rectangle inside the shape runs from the rightmost left-edge to the
     * leftmost right-edge. Rows covering the text are taken first, since
     * covering the text is the point, then the span grows outwards for as long
     * as it costs no width.
     */
    private fun inscribe(rows: Map<Int, IntArray>, start: TextBounds): TextBounds? {
        val textRows = rows.keys.filter { it in start.top..start.bottom }.sorted()
        if (textRows.isEmpty()) return null

        var left = textRows.maxOf { rows.getValue(it)[0] }
        var right = textRows.minOf { rows.getValue(it)[1] }
        if (right <= left) return null

        var top = textRows.first()
        var bottom = textRows.last()

        // Outwards while the shape stays at least this wide — on a round bubble
        // the rows near the top and bottom pinch in, and following them would
        // trade the whole width for a little height.
        while (true) {
            val above = rows[top - STEP]
            if (above == null || above[0] > left || above[1] < right) break
            top -= STEP
        }
        while (true) {
            val below = rows[bottom + STEP]
            if (below == null || below[0] > left || below[1] < right) break
            bottom += STEP
        }

        // Exclusive edges one pixel past the last cell known to be background,
        // not one whole step past it. A step's worth would be assuming three
        // pixels nobody sampled, and on a round bubble those three are exactly
        // where the outline is.
        return TextBounds(
            left = left,
            top = top,
            right = right + 1,
            bottom = bottom + 1,
            space = start.space,
        )
    }

    /**
     * Background points inside the text's own box — the gaps between glyphs and
     * between columns. Seeding from inside means the flood starts where the
     * text is and spreads to whatever contains it.
     */
    private fun seeds(start: TextBounds, isBackground: (Int, Int) -> Boolean): List<Pair<Int, Int>> {
        val found = ArrayList<Pair<Int, Int>>()
        var y = start.top
        while (y < start.bottom) {
            var x = start.left
            while (x < start.right) {
                if (isBackground(x, y)) found += x to y
                x += STEP
            }
            y += STEP
        }
        return found
    }

    private fun neighbours(x: Int, y: Int): List<Pair<Int, Int>> = listOf(
        x - STEP to y,
        x + STEP to y,
        x to y - STEP,
        x to y + STEP,
    )

    private fun maxCells(limit: TextBounds): Int {
        val widthCells = limit.width * MAX_FRAME_NUMERATOR / MAX_FRAME_DENOMINATOR / STEP
        val heightCells = limit.height * MAX_FRAME_NUMERATOR / MAX_FRAME_DENOMINATOR / STEP
        return (widthCells * heightCells).coerceAtLeast(1)
    }

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
}
