package com.babel.domain.game

import com.babel.core.model.RowGradient
import com.babel.core.model.TextBounds
import com.babel.domain.game.GameFrameLook.GRID_COLUMNS
import com.babel.domain.game.GameFrameLook.GRID_ROWS
import com.babel.domain.game.GameFrameLook.INK_CONTRAST
import com.babel.domain.game.GameFrameLook.SAMPLE_STEP
import com.babel.domain.game.GamePixels.blend
import com.babel.domain.game.GamePixels.blue
import com.babel.domain.game.GamePixels.green
import com.babel.domain.game.GamePixels.luma
import com.babel.domain.game.GamePixels.red
import com.babel.domain.game.GamePixels.rgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Panel continuity, backdrop smoothness error, changed-region blank-edge
 * expansion, and row-by-row erase gradient sampling over [FramePixels].
 */
object GamePanelSampler {

    /** Middle of 0..255 brightness: a panel below it carries light ink. */
    const val MID_LUMA = 128.0

    /** How far (0..255) an end's colour may stand from the row's panel and still be panel. */
    const val EDGE_PANEL_LUMA = 30.0

    /** A panel this far from the line's own is a different one: a bubble's page. */
    const val OTHER_PANEL_LUMA = 80.0
    const val SIDE_PANEL_LUMA = 40.0
    const val PAGE_EDGES = 2
    const val BARE_LUMA = 10.0

    /** How far the panel under a painted-out line may change before it is painted again. */
    const val PANEL_CHANGED_LUMA = 10.0

    /** Columns averaged at each end of a row for its colour. */
    const val EDGE_SAMPLE_COLUMNS = 3

    /** A cell of slack above and below, so a line's edge is not cut off. */
    const val BAND_MARGIN_CELLS = 1

    const val REGION_GAP_CELLS = 3
    const val BLANK_REACH_OF_HEIGHT = 0.08
    const val BLANK_STRAY = 1
    const val BLANK_MIN_RUN = 4
    const val BLANK_MAX_CHANGES = 24

    /**
     * [box] without the columns at either end that stand off the line's
     * panel: a chat bubble's pointed tail the recogniser's box took in. A
     * left bubble's 「いえ」 had its box start 15 px out on the tail, and its
     * translation was set from there, out of the bubble. A column is off the
     * panel where the rows just above and below it are both a different panel
     * from the line's — the page around the bubble.
     */
    fun onItsPanel(frame: FramePixels, box: TextBounds): TextBounds {
        if (box.top < 2 || box.bottom + 1 >= frame.height || box.left < 0 || box.right > frame.width || box.width < 4) return box
        val above = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, box.top - 2, box.width, 1) }
        val below = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, box.bottom + 1, box.width, 1) }
        val lumas = (above.map { luma(it) } + below.map { luma(it) }).sorted()
        val panel = lumas[lumas.size / 2]
        // Only round a panel: over art nothing above or below is one colour.
        if (lumas.count { abs(it - panel) <= EDGE_PANEL_LUMA } * 3 < lumas.size * 2) return box
        fun off(x: Int) = abs(luma(above[x]) - panel) > OTHER_PANEL_LUMA && abs(luma(below[x]) - panel) > OTHER_PANEL_LUMA
        var left = 0
        while (left < box.width / 4 && off(left)) left++
        var right = box.width
        while (right > box.width * 3 / 4 && off(right - 1)) right--
        if (left == 0 && right == box.width) return box
        return TextBounds(box.left + left, box.top, box.left + right, box.bottom, box.space)
    }

    /**
     * How plain the line's backdrop is: the band just above and below it,
     * predicted row by row from the pixels at the line's two ends. Mean
     * absolute error in brightness; see [GameLine.backdropError].
     */
    fun backdropError(frame: FramePixels, width: Int, height: Int, box: TextBounds): Double {
        val pad = max(6, box.height / 4)
        val left = box.left - 1
        val right = box.right
        if (left < 0 || right >= width) return Double.MAX_VALUE
        var sum = 0.0
        var count = 0
        val rowPixels = IntArray(width)
        val rows = (box.top - pad until box.top) + (box.bottom until box.bottom + pad)
        for (y in rows) {
            if (y < 0 || y >= height) continue
            // One row at a time: the whole frame as an int array is 18 MB.
            frame.getPixels(rowPixels, 0, width, 0, y, width, 1)
            val pixels = rowPixels
            val row = 0
            var l = luma(pixels[row + left])
            var r = luma(pixels[row + right])
            // One end off the panel the rest of the row is on: the box ran a
            // few pixels past a bubble the text nearly fills — MomoTalk's
            // 「それはよかった」 ended on the white page, and its blue bubble
            // scored 63, as art. That end takes the other's colour; over art
            // the row between them is what still tells.
            val middle = (box.left until right step SAMPLE_STEP).map { luma(pixels[row + it]) }.sorted().let { it[it.size / 2] }
            if (abs(r - middle) > EDGE_PANEL_LUMA && abs(l - middle) <= EDGE_PANEL_LUMA) r = l
            else if (abs(l - middle) > EDGE_PANEL_LUMA && abs(r - middle) <= EDGE_PANEL_LUMA) l = r
            // Both ends off it, and alike: the box ran past the bubble at
            // both ends, onto the page around it. A graded panel's ends
            // differ, and stay as they are.
            else if (abs(l - middle) > EDGE_PANEL_LUMA && abs(r - middle) > EDGE_PANEL_LUMA && abs(l - r) <= EDGE_PANEL_LUMA) {
                l = middle
                r = middle
            }
            val span = (right - left).coerceAtLeast(1)
            var x = box.left
            while (x < right) {
                val t = (x - left).toDouble() / span
                sum += abs(luma(pixels[row + x]) - (l * (1 - t) + r * t))
                count++
                x += SAMPLE_STEP
            }
        }
        return if (count == 0) Double.MAX_VALUE else sum / count
    }

    /** The middle colour of the piece's edge columns, where the cut put backdrop. */
    fun backdropOf(piece: FramePixels): Int {
        val edge = IntArray(piece.height * 2)
        piece.getPixels(edge, 0, 1, 0, 0, 1, piece.height)
        piece.getPixels(edge, piece.height, 1, piece.width - 1, 0, 1, piece.height)
        return edge.sortedBy { luma(it) }[edge.size / 2]
    }

    /**
     * Per row of [box] grown by [margin] along it and [rowMargin] up and
     * down, the colour just outside it at each end: the mean of a few
     * columns, so one stray pixel of outline does not colour a whole row.
     * Null where the box runs off the frame.
     */
    fun rowGradient(frame: FramePixels, box: TextBounds, margin: Int, wantedRowMargin: Int, fromStartOnly: Boolean = false): RowGradient? {
        // Not past the panel the line is on: a bubble the text nearly fills
        // — MomoTalk's 「それはよかった」 — ends within the margin, the rows
        // sampled above and below were the white page, and the bubble was
        // painted out white. Over art the rows outside are art anyway.
        val rowMargin = if (fromStartOnly) wantedRowMargin else onPanelRows(frame, box, wantedRowMargin)
        val top = box.top - rowMargin
        val bottom = box.bottom + rowMargin
        val left = box.left - margin - EDGE_SAMPLE_COLUMNS
        val right = box.right + margin + EDGE_SAMPLE_COLUMNS
        if (top < 0 || bottom > frame.height || left < 0 || right > frame.width) return null
        val rows = bottom - top
        val column = IntArray(rows * EDGE_SAMPLE_COLUMNS)
        fun edge(x: Int): List<Int> {
            frame.getPixels(column, 0, EDGE_SAMPLE_COLUMNS, x, top, EDGE_SAMPLE_COLUMNS, rows)
            return List(rows) { y ->
                var r = 0
                var g = 0
                var b = 0
                for (i in 0 until EDGE_SAMPLE_COLUMNS) {
                    val p = column[y * EDGE_SAMPLE_COLUMNS + i]
                    r += red(p); g += green(p); b += blue(p)
                }
                rgb(r / EDGE_SAMPLE_COLUMNS, g / EDGE_SAMPLE_COLUMNS, b / EDGE_SAMPLE_COLUMNS)
            }
        }
        // The panel per row from above and below the text, not from inside
        // it alone: a pixel font's strokes fill more than half of some rows,
        // and their middle colour was the ink — drawn as lines through the
        // patch. Inside the row only what is near that panel colour counts.
        val above = rowMiddle(frame, box, (top - 1).coerceAtLeast(0))
        val below = rowMiddle(frame, box, (top + rows).coerceAtMost(frame.height - 1))
        // Above and below not one panel — a chat sender's name sits on the
        // page with the bubble just under it — and the blend between them
        // was neither: its lower rows matched the dark type, and the patch
        // came out grey. The one of the two the line itself is mostly on is
        // its panel.
        val band = if (abs(luma(above) - luma(below)) > EDGE_PANEL_LUMA) {
            List(rows) { commoner(frame, box, above, below) }
        } else {
            List(rows) { i -> blend(above, below, i / (rows - 1f).coerceAtLeast(1f)) }
        }
        val inside = insideRows(frame, box, top, rows, near = band, overArt = fromStartOnly)
        if (fromStartOnly) return RowGradient(inside, inside, margin, rowMargin)
        val (l, r) = BackdropEdges.repaired(edge(left), edge(box.right + margin))
        // An end that is not the panel the text sits on — a button's border,
        // a bevel's highlight just outside the box — takes the row's own
        // panel colour instead: Shattered Pixel Dungeon's buttons came out
        // striped where rows of border were interpolated across the patch.
        fun fit(ends: List<Int>) = ends.mapIndexed { i, c ->
            if (abs(luma(c) - luma(inside[i])) > EDGE_PANEL_LUMA) inside[i] else c
        }
        return RowGradient(fit(l), fit(r), margin, rowMargin)
    }

    fun panelsOf(frame: FramePixels): GameLayout.Panels = object : GameLayout.Panels {
        override fun joined(above: GameLine, below: GameLine) = onePanel(frame, above, below)
        override fun beside(a: GameLine, b: GameLine) = sidePanel(frame, a, b)
        override fun across(line: GameLine, fromX: Int) = onePage(frame, line, fromX)
    }

    /**
     * Whether [below] sits on the panel [above] does: the gap between them,
     * over [below]'s width, the colour of [above]'s own row — not the row
     * just under it, which in a bubble the text nearly fills is the page
     * already, as the gap to the home screen's 「ショップ」 was. Only a clearly
     * different panel says no — a translucent box over moving art is not one
     * colour either.
     */
    fun onePanel(frame: FramePixels, above: GameLine, below: GameLine): Boolean {
        val gapTop = above.bounds.bottom
        val gapBottom = below.bounds.top
        if (gapBottom - gapTop < 2) return true
        val y = ((gapTop + gapBottom) / 2).coerceIn(0, frame.height - 1)
        val under = ((above.bounds.top + above.bounds.bottom) / 2).coerceIn(0, frame.height - 1)
        val left = below.bounds.left.coerceIn(0, frame.width - 1)
        val right = below.bounds.right.coerceIn(left + 1, frame.width)
        val aLeft = above.bounds.left.coerceIn(0, frame.width - 1)
        val aRight = above.bounds.right.coerceIn(aLeft + 1, frame.width)
        fun middle(x0: Int, x1: Int, row: Int): Double {
            val px = IntArray(x1 - x0).also { frame.getPixels(it, 0, it.size, x0, row, it.size, 1) }
            return px.map { luma(it) }.sorted()[px.size / 2]
        }
        // Over the columns both lines share: a narration's first line ran the
        // box's width, its right end over the brighter floor showing through.
        val from = maxOf(left, aLeft)
        val to = minOf(right, aRight)
        // And not only through its middle, which on a dense line of bold
        // type is mostly ink (luma 240 against the panel's 40): its top and
        // bottom rows show the panel too.
        if (to - from >= above.typeHeight * 2) {
            val gap = middle(from, to, y)
            val rows = listOf(under, (above.bounds.top + 2).coerceIn(0, frame.height - 1), (above.bounds.bottom - 2).coerceIn(0, frame.height - 1))
            return rows.any { row -> abs(gap - middle(from, to, row)) <= OTHER_PANEL_LUMA }
        }
        return abs(middle(left, right, y) - middle(aLeft, aRight, under)) <= OTHER_PANEL_LUMA
    }

    /**
     * The parts of [frame] that changed since the last reading, as boxes with
     * edges on blank panel; null where an edge finds none, and the caller
     * reads the band instead.
     */
    fun changedBoxes(frame: FramePixels, lastGrid: IntArray?, lastLines: List<GameLine>, grid: IntArray): List<IntArray>? {
        val cellWidth = frame.width.toFloat() / GRID_COLUMNS
        val cellHeight = frame.height.toFloat() / GRID_ROWS
        val out = mutableListOf<IntArray>()
        for (region in ChangedBand.regions(lastGrid, grid, GRID_COLUMNS, gap = REGION_GAP_CELLS)) {
            var box = ChangedBand.widenedBox(
                lastLines,
                ((region.columns.first - BAND_MARGIN_CELLS) * cellWidth).toInt(),
                ((region.rows.first - BAND_MARGIN_CELLS) * cellHeight).toInt(),
                ((region.columns.last + 1 + BAND_MARGIN_CELLS) * cellWidth).toInt(),
                ((region.rows.last + 1 + BAND_MARGIN_CELLS) * cellHeight).toInt(),
                marginX = cellWidth.toInt(),
                marginY = cellHeight.toInt() / 2,
                frameWidth = frame.width,
                frameHeight = frame.height,
            )
            // Where an edge finds no blank panel, the part is read as a band
            // the frame's width instead, as a whole change of those rows is.
            box = onBlank(frame, box) ?: run {
                val (t, b) = ChangedBand.widened(lastLines, box[1], box[3], margin = cellHeight.toInt() / 2, frameHeight = frame.height)
                intArrayOf(0, t, frame.width, b)
            }
            out += box
        }
        // Parts that came to overlap are read as one.
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in out.indices) for (j in i + 1 until out.size) {
                val a = out[i]
                val b = out[j]
                if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) {
                    out[i] = intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
                    out.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
        return out
    }

    /** [box] with each edge moved out to the nearest blank column or row, or null where there is none near. */
    fun onBlank(frame: FramePixels, box: IntArray): IntArray? {
        var (l, t, r, b) = box.toList()
        val reach = (frame.height * BLANK_REACH_OF_HEIGHT).toInt()
        // Blank is panels, not one colour: a column down a chat list
        // crosses its header, its rows, the selected row's shade — a few
        // long runs. Type is many short ones.
        fun blank(lumas: List<Double>): Boolean {
            var changes = 0
            var run = 0
            var shortRuns = 0
            for (i in 1 until lumas.size) {
                if (abs(lumas[i] - lumas[i - 1]) > BARE_LUMA) {
                    changes++
                    if (run < BLANK_MIN_RUN) shortRuns++
                    run = 0
                } else {
                    run++
                }
            }
            return shortRuns <= BLANK_STRAY && changes <= BLANK_MAX_CHANGES
        }
        fun columnBlank(x: Int): Boolean {
            if (x <= 0 || x >= frame.width - 1) return true
            val c = IntArray(b - t).also { frame.getPixels(it, 0, 1, x, t, 1, b - t) }
            return blank((c.indices step 2).map { luma(c[it]) })
        }
        fun rowBlank(y: Int): Boolean {
            if (y <= 0 || y >= frame.height - 1) return true
            val row = IntArray(r - l).also { frame.getPixels(it, 0, it.size, l, y, it.size, 1) }
            return blank((row.indices step 2).map { luma(row[it]) })
        }
        // An edge with no blank near it is no edge: a speech bubble just
        // appearing over the art was cut where nothing had been read yet, and
        // read as two lines, two translations stacked on each other. The
        // caller reads such a part as a band; moving art is left out by the
        // art mask instead.
        var n = 0
        while (!rowBlank(t) && n++ < reach) t--
        if (n > reach) return null
        n = 0
        while (!rowBlank(b - 1) && n++ < reach) b++
        if (n > reach) return null
        n = 0
        while (!columnBlank(l) && n++ < reach) l--
        if (n > reach) return null
        n = 0
        while (!columnBlank(r - 1) && n++ < reach) r++
        if (n > reach) return null
        return intArrayOf(l.coerceAtLeast(0), t.coerceAtLeast(0), r.coerceAtMost(frame.width), b.coerceAtMost(frame.height))
    }

    /**
     * Whether the row from [fromX] to [line]'s start, at its middle, crosses
     * at most the edge of the line's own bubble: a window's border into the
     * art behind it is more.
     */
    fun onePage(frame: FramePixels, line: GameLine, fromX: Int): Boolean {
        val from = fromX.coerceIn(0, frame.width - 1)
        val to = line.bounds.left.coerceIn(from, frame.width)
        if (to - from < 4) return true
        val y = ((line.bounds.top + line.bounds.bottom) / 2).coerceIn(0, frame.height - 1)
        val row = IntArray(to - from).also { frame.getPixels(it, 0, it.size, from, y, it.size, 1) }
        var edges = 0
        var last = luma(row[0])
        for (x in SAMPLE_STEP until row.size step SAMPLE_STEP) {
            val l = luma(row[x])
            if (abs(l - last) > SIDE_PANEL_LUMA) edges++
            last = l
        }
        // And the line stands on that page, not past its edge: the page's
        // colour where the row starts is the colour just before the line.
        // and just after it: a badge past the window's edge had the page up
        // to its left side and the art beyond its right.
        val page = luma(row[0])
        val before = luma(row[(row.size - 1 - SAMPLE_STEP * 2).coerceAtLeast(0)])
        val afterX = (line.bounds.right + line.bounds.height / 2).coerceAtMost(frame.width - 1)
        val after = luma(frame.getPixel(afterX, y))
        return edges <= PAGE_EDGES && abs(before - page) <= SIDE_PANEL_LUMA && abs(after - page) <= SIDE_PANEL_LUMA
    }

    /** Whether the space between two lines on one row is the panel either stands on. */
    fun sidePanel(frame: FramePixels, a: GameLine, b: GameLine): Boolean {
        val (l, r) = if (a.bounds.left <= b.bounds.left) a to b else b to a
        val from = l.bounds.right.coerceIn(0, frame.width - 1)
        val to = r.bounds.left.coerceIn(from, frame.width)
        if (to - from < 4) return true
        val y = ((max(a.bounds.top, b.bounds.top) + min(a.bounds.bottom, b.bounds.bottom)) / 2).coerceIn(0, frame.height - 1)
        val gap = IntArray(to - from).also { frame.getPixels(it, 0, it.size, from, y, it.size, 1) }.map { luma(it) }.sorted()
        fun own(line: GameLine): Double {
            val x0 = line.bounds.left.coerceIn(0, frame.width - 1)
            val x1 = line.bounds.right.coerceIn(x0 + 1, frame.width)
            val row = IntArray(x1 - x0).also { frame.getPixels(it, 0, it.size, x0, y, it.size, 1) }
            return row.map { luma(it) }.sorted()[row.size / 2]
        }
        val middle = gap[gap.size / 2]
        // Closer than another panel: a dimmed list behind a pop-up is the
        // pop-up's own colour darkened by a half, not by a whole panel's step.
        // And no edge between them: a pop-up's border and its shadow lay
        // between its line and the white reply button behind it, alike in
        // colour on either side.
        val edges = gap.count { abs(it - middle) > SIDE_PANEL_LUMA }
        return abs(middle - own(a)) <= SIDE_PANEL_LUMA && abs(middle - own(b)) <= SIDE_PANEL_LUMA && edges * 20 < gap.size
    }

    /** How many of [wanted] rows above and below [box] are still the panel its middle row is on. */
    fun onPanelRows(frame: FramePixels, box: TextBounds, wanted: Int): Int {
        if (box.top - wanted - 1 < 0 || box.bottom + wanted + 1 > frame.height) return wanted
        val own = luma(rowMiddle(frame, box, box.top + box.height / 2))
        // Another panel, not a shade of this one: Blue Archive's dialogue
        // box darkens toward its foot, and cut at the first shade its patches
        // lost a third of their height.
        fun panel(y: Int) = abs(luma(rowMiddle(frame, box, y)) - own) <= OTHER_PANEL_LUMA
        var up = 0
        while (up < wanted && panel(box.top - up - 2)) up++
        var down = 0
        while (down < wanted && panel(box.bottom + down + 1)) down++
        return min(up, down)
    }

    /** The panel's brightness just above and just below [box]. */
    fun panelAround(frame: FramePixels, box: TextBounds): Pair<Double, Double> {
        val clipped = TextBounds(
            box.left.coerceIn(0, frame.width - 1),
            box.top,
            box.right.coerceIn(box.left.coerceIn(0, frame.width - 1) + 1, frame.width),
            box.bottom,
            box.space,
        )
        return luma(rowMiddle(frame, clipped, (box.top - 1).coerceIn(0, frame.height - 1))) to
            luma(rowMiddle(frame, clipped, box.bottom.coerceIn(0, frame.height - 1)))
    }

    fun repainted(was: Pair<Double, Double>, now: Pair<Double, Double>): Boolean =
        // Above and below both: a shade laid over a row changes the two. The
        // list's last row stands at the window's foot over the home screen's
        // animation, and was painted again every frame for its lower edge.
        abs(was.first - now.first) > PANEL_CHANGED_LUMA && abs(was.second - now.second) > PANEL_CHANGED_LUMA

    fun rowMiddle(frame: FramePixels, box: TextBounds, y: Int): Int {
        val row = IntArray(box.width)
        frame.getPixels(row, 0, box.width, box.left, y, box.width, 1)
        return (0 until box.width step SAMPLE_STEP).map { row[it] }.sortedBy { luma(it) }.let { it[it.size / 2] }
    }

    /** Whichever of [a] and [b] more of [box]'s pixels are near. */
    fun commoner(frame: FramePixels, box: TextBounds, a: Int, b: Int): Int {
        val row = IntArray(box.width)
        var nearA = 0
        var nearB = 0
        for (y in box.top until box.bottom step SAMPLE_STEP) {
            frame.getPixels(row, 0, box.width, box.left, y, box.width, 1)
            for (x in 0 until box.width step SAMPLE_STEP) {
                val l = luma(row[x])
                if (abs(l - luma(a)) <= EDGE_PANEL_LUMA) nearA++
                if (abs(l - luma(b)) <= EDGE_PANEL_LUMA) nearB++
            }
        }
        return if (nearA >= nearB) a else b
    }

    /**
     * Each row's own panel colour, from inside the line: the middle colour of
     * the row by brightness, which is panel wherever the type is the
     * minority. Over art the ends of a line are not panel — a name's tab
     * fades into the picture, and its pointed end is dark — while what lies
     * between the glyphs is.
     */
    fun insideRows(frame: FramePixels, box: TextBounds, top: Int, rows: Int, near: List<Int>, overArt: Boolean): List<Int> {
        val w = box.width
        val row = IntArray(w)
        val all = List(rows) { i ->
            val y = (top + i).coerceIn(0, frame.height - 1)
            frame.getPixels(row, 0, w, box.left, y, w, 1)
            val sampled = (0 until w step SAMPLE_STEP).map { row[it] }.sortedBy { luma(it) }
            val panelLike = sampled.filter { abs(luma(it) - luma(near[i])) <= EDGE_PANEL_LUMA }
            // Too little of the row near the band — art behind a name's tab —
            // and the row's own middle colour is the better guess.
            // Over art the rows outside the line are art too, and say nothing
            // about the tab under a name: there, the row alone decides.
            when {
                !overArt && panelLike.size * 4 >= sampled.size -> panelLike[panelLike.size / 2]
                // Over art, but most of the row is the colour above and below
                // it: a chat sender's name on the white page beside an avatar
                // scored as art, and its patch came out grey.
                overArt && panelLike.size * 2 >= sampled.size -> panelLike[panelLike.size / 2]
                // Over art, the middle of what is not type: the middle of a row
                // full of yellow type was yellow, and was drawn as lines
                // through the patch.
                overArt -> {
                    // The row without its ink: everything near the row's
                    // lightest (light type) or darkest pixel left out.
                    val light = luma(near[i]) < MID_LUMA
                    val extreme = luma(if (light) sampled.last() else sampled.first())
                    val rest = sampled.filter { abs(luma(it) - extreme) > INK_CONTRAST / 2 }
                    if (rest.isEmpty()) sampled[sampled.size / 2] else rest[rest.size / 2]
                }
                // A row the type fills: the panel above and below is the panel.
                else -> near[i]
            }
        }
        return all
    }
}
