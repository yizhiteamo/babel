package com.babel.domain.game

import com.babel.core.model.TextBounds
import com.babel.domain.game.GameFrameLook.INK_CONTRAST
import com.babel.domain.game.GamePanelSampler.BARE_LUMA
import com.babel.domain.game.GamePanelSampler.EDGE_SAMPLE_COLUMNS
import com.babel.domain.game.GamePanelSampler.MID_LUMA
import com.babel.domain.game.GamePanelSampler.OTHER_PANEL_LUMA
import com.babel.domain.game.GamePixels.blue
import com.babel.domain.game.GamePixels.colourDistance
import com.babel.domain.game.GamePixels.green
import com.babel.domain.game.GamePixels.luma
import com.babel.domain.game.GamePixels.mean
import com.babel.domain.game.GamePixels.red
import com.babel.domain.game.GamePixels.rgb
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class Strokes(val outline: Int?, val weight: Float?)

/**
 * Stateless per-line pixel measurements over [FramePixels]: type height,
 * inked column runs, stroke weight and outline, single/dual ink colours,
 * ink extent expansion/shrinking, plain room past a line, and leading
 * icon/bar/mark geometry.
 */
object GameLineStyle {

    const val MIN_ICON_PX = 6
    const val BAR_GAP_PX = 4

    /** Ink this far (0–441) from the line's own is a second ink: blue figures against grey words measured over 150. */
    const val ACCENT_DISTANCE = 110.0

    /** And this share of the line's ink, so a glyph's anti-aliased edge is not one. */
    const val MIN_ACCENT_PERCENT = 5
    const val MIN_TWO_INK_PIXELS = 60

    /** Colour distance (0–441) from the panel that makes a column a coloured mark: the cyan bar on white measured 128. */
    const val MARK_COLOUR = 90.0

    /**
     * Ink colours (0–441) this far apart make a first glyph a badge: the
     * heart against a white name measured 64–175, a name's own first
     * letter against the next 6–41. A kana is then confirmed by reading
     * the badge's number.
     */
    const val BADGE_COLOUR = 55.0

    /**
     * Clear space along the line, beyond its ink: the ink's extent already
     * covers the glyphs, and more reached into the piece beside it.
     */
    const val SIDE_MARGIN_PX = 2

    /** How far outside a stroke its outline is looked for, and how far from the panel it must be. */
    const val OUTLINE_PROBE_PX = 2
    const val OUTLINE_CONTRAST = 25.0

    /** The share of ink pixels, strongest first, that give the ink its colour. */
    const val INK_CORE_PERCENT = 30

    /** The least of its one-line box a line's type fills. */
    const val MIN_TYPE_OF_BOX = 0.67

    /** How far above a line, in type heights, ruby is looked for, and the gap it may leave (1/n). */
    const val RUBY_REACH = 0.6
    const val RUBY_GAP_DIVISOR = 4

    /** How much further, in type heights, the ink is followed along a line than across it. */
    const val ALONG_REACH = 8

    /** Ink pixels that make a row or column beyond the box part of the line. */
    const val EXTENT_MIN_INK = 2

    /** What is kept of an empty end a box is cut back from. */
    const val SHRINK_SLACK_PX = 2
    const val BARE_STRAY_PX = 2

    /** How far past a box's end its last glyph's edge may still reach. */
    const val LEAD_IN_PX = 6

    /** A row is inside the type when it holds this fraction (1/n) of the busiest row's ink. */
    const val TYPE_ROW_SHARE = 40

    /** Empty rows allowed inside one line of type; ruby sits further off. */
    const val TYPE_ROW_GAP = 2

    /**
     * Where an icon just before [box] ends, when its ink is another colour
     * than the line's: the patch stops there. The affection badge before
     * 「ユズ」 was outside the recogniser's box, nothing read it, and the
     * name's patch grew left over it and painted it out.
     */
    fun iconBefore(frame: FramePixels, box: TextBounds, typeHeight: Int): Int? {
        val reach = (typeHeight * 1.5).toInt()
        val left = (box.left - reach).coerceAtLeast(0)
        if (box.left - left < 4 || box.top < 0 || box.bottom > frame.height) return null
        val own = inkColour(frame, box.left, box.top, min(box.right, box.left + typeHeight * 2), box.bottom) ?: return null
        val column = IntArray(box.height)
        var x = box.left - 1
        while (x >= left) {
            frame.getPixels(column, 0, 1, x, box.top, 1, box.height)
            val middle = column.map { luma(it) }.sorted()[box.height / 2]
            val ink = column.filter { abs(luma(it) - middle) >= INK_CONTRAST }
            if (ink.size >= 2) {
                val r = ink.sumOf { red(it) }.toDouble() / ink.size - own[0]
                val g = ink.sumOf { green(it) }.toDouble() / ink.size - own[1]
                val bl = ink.sumOf { blue(it) }.toDouble() / ink.size - own[2]
                if (kotlin.math.sqrt(r * r + g * g + bl * bl) <= BADGE_COLOUR) return null
                // An icon is wider than a panel's edge: a dialogue box's
                // border, a column or two, is not one.
                return if (inkedColumns(frame, (x - MIN_ICON_PX + 1).coerceAtLeast(0), box.top, x + 1, box.bottom) >= MIN_ICON_PX) x + 1 else null
            }
            x--
        }
        return null
    }

    /**
     * How far into [box] a bar at its start reaches with the gap after it, or
     * null: a first run of ink no wider than a quarter of the line's height,
     * then blank columns.
     */
    fun bar(frame: FramePixels, box: TextBounds): Int? {
        val h = box.height
        val column = IntArray(h)
        fun inked(x: Int): Boolean {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            return column.count { abs(luma(it) - middle) >= INK_CONTRAST } * 2 >= h
        }
        var x = box.left
        while (x < box.right && !inked(x)) x++
        val start = x
        while (x < box.right && inked(x)) x++
        if (x == start || x - start > h / 4) return null
        val end = x
        while (x < box.right && column.let { frame.getPixels(it, 0, 1, x, box.top, 1, h); val m = it.map { p -> luma(p) }.sorted()[h / 2]; it.none { p -> abs(luma(p) - m) >= INK_CONTRAST } }) x++
        return if (x - end >= BAR_GAP_PX) x - box.left else null
    }

    /**
     * Where a coloured mark inside [box], before its first ink, ends: a
     * pop-up title's accent bar, light cyan on white, too pale to count as
     * ink, sat inside the recogniser's box and was painted out with it.
     * Null when there is none.
     */
    fun colouredMarkInside(frame: FramePixels, box: TextBounds): Int? {
        val h = box.height
        if (h <= 0 || box.left < 0 || box.right > frame.width || box.top < 0 || box.bottom > frame.height) return null
        val column = IntArray(h)
        fun inkAt(x: Int): Boolean {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            return column.count { abs(luma(it) - middle) >= INK_CONTRAST } >= 2
        }
        val firstInk = (box.left until box.right).firstOrNull(::inkAt) ?: return null
        if (firstInk - box.left < 4) return null
        frame.getPixels(column, 0, 1, firstInk - 2, box.top, 1, h)
        val panel = column[h / 2]
        var end: Int? = null
        for (x in box.left until firstInk - 2) {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val marked = column.count { p ->
                val dr = red(p) - red(panel)
                val dg = green(p) - green(panel)
                val db = blue(p) - blue(panel)
                dr * dr + dg * dg + db * db > MARK_COLOUR * MARK_COLOUR
            }
            if (marked * 2 >= h) end = x + 1
        }
        return end?.plus(SIDE_MARGIN_PX)
    }

    /** How many columns of a box hold ink against their own middle value. */
    fun inkedColumns(frame: FramePixels, left: Int, top: Int, right: Int, bottom: Int): Int {
        val h = bottom - top
        if (right <= left || h <= 0) return 0
        val column = IntArray(h)
        return (left until right).count { x ->
            frame.getPixels(column, 0, 1, x, top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            column.count { abs(luma(it) - middle) >= INK_CONTRAST } >= 2
        }
    }

    /** The mean colour (r, g, b) of the ink in a box, by each column's middle value; null without ink. */
    fun inkColour(frame: FramePixels, left: Int, top: Int, right: Int, bottom: Int): DoubleArray? {
        val h = bottom - top
        if (right <= left || h <= 0) return null
        val column = IntArray(h)
        val sum = DoubleArray(3)
        var n = 0
        for (x in left until right) {
            frame.getPixels(column, 0, 1, x, top, 1, h)
            val middle = column.map { luma(it) }.sorted()[h / 2]
            for (p in column) if (abs(luma(p) - middle) >= INK_CONTRAST) {
                sum[0] += red(p).toDouble(); sum[1] += green(p).toDouble(); sum[2] += blue(p).toDouble(); n++
            }
        }
        return if (n == 0) null else DoubleArray(3) { sum[it] / n }
    }

    /**
     * The tallest band of inked rows in [box], a gap of [TYPE_ROW_GAP] rows
     * allowed inside it: the height of the type, without ruby over it or the
     * slack the recogniser's box sometimes takes in. See [GameLine.typeHeight].
     */
    fun typeHeight(frame: FramePixels, box: TextBounds): Int {
        val h = box.height
        if (h <= 0 || box.width <= 0) return h
        // The panel is read just outside the box, above and below each
        // column. The column's own middle value is not the panel wherever a
        // stroke fills most of a tight box, and judged against it those
        // strokes vanished: a line of dialogue measured 37 px for 55.
        val above = (box.top - 2).coerceAtLeast(0)
        val below = (box.bottom + 1).coerceAtMost(frame.height - 1)
        val outsideTop = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, above, box.width, 1) }
        val outsideBottom = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, below, box.width, 1) }
        // Where above and below differ, the one nearer the line's own middle
        // colour: a bubble the text nearly fills had the white page just
        // under its box, the mean of blue and white read as a light panel,
        // no white type stood out of it, and 「くっ……」 measured as tall as
        // its box — 63 px for 40.
        val middle = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, box.top + h / 2, box.width, 1) }
            .map { luma(it) }.sorted()[box.width / 2]
        val panel = DoubleArray(box.width) {
            val a = luma(outsideTop[it])
            val b = luma(outsideBottom[it])
            if (abs(a - b) <= OTHER_PANEL_LUMA) (a + b) / 2 else if (abs(a - middle) <= abs(b - middle)) a else b
        }
        // Ink stands out of its panel: light on a dark one.
        val sign = if (panel.average() < MID_LUMA) 1.0 else -1.0

        val column = IntArray(h)
        val ink = IntArray(h)
        for (x in 0 until box.width) {
            frame.getPixels(column, 0, 1, box.left + x, box.top, 1, h)
            for (y in 0 until h) if ((luma(column[y]) - panel[x]) * sign >= INK_CONTRAST) ink[y]++
        }
        // A row is inside the type when it holds a little of the busiest
        // row's ink: across a long line only a few glyphs reach the top rows.
        // The tallest band of such rows, a few empty ones allowed inside it;
        // ruby over the line stands further off and is not counted.
        val enough = max(2, (ink.maxOrNull() ?: 0) / TYPE_ROW_SHARE)
        var best = 0
        var start = -1
        var last = -1
        for (y in 0 until h) {
            if (ink[y] < enough) continue
            if (start < 0 || y - last > TYPE_ROW_GAP + 1) start = y
            last = y
            best = max(best, y - start + 1)
        }
        // Never far under the recogniser's box: over art the rows outside the
        // box are not panel, the ink is judged against the wrong side, and a
        // name tab's type measured 32 px in a 75 px box — its translation was
        // set at half the size of the name it replaced. Type fills at least
        // two thirds of a box around one line.
        return if (best == 0) h else max(best, (h * MIN_TYPE_OF_BOX).toInt())
    }

    /**
     * Runs of inked columns across [box], each with its mean ink colour.
     *
     * Ink is what stands out of its column **on the line's own side**: lighter
     * than the backdrop if most of the contrast is lighter, darker otherwise.
     * Counting both took in the glyphs' dark outline and the art showing
     * through it, and a thin white リ came out grey — measured, the name
     * イオリ was cut after イオ, every time, and チナツ not at all.
     */
    fun inkRuns(frame: FramePixels, box: TextBounds, lighterInk: Boolean? = null): List<GameLayout.InkRun> {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0) return emptyList()
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val background = DoubleArray(w)
        val column = DoubleArray(h)
        var lighter = 0L
        var darker = 0L
        for (x in 0 until w) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            background[x] = bg
            for (y in 0 until h) {
                if (column[y] - bg >= INK_CONTRAST) lighter++ else if (bg - column[y] >= INK_CONTRAST) darker++
            }
        }
        val sign = if (lighterInk ?: (lighter >= darker)) 1.0 else -1.0

        val runs = mutableListOf<GameLayout.InkRun>()
        var start = -1
        var r = 0L
        var g = 0L
        var bl = 0L
        var n = 0L
        fun close(end: Int) {
            if (start >= 0 && n > 0) {
                runs += GameLayout.InkRun(box.left + start, box.left + end, (r / n).toInt(), (g / n).toInt(), (bl / n).toInt(), n.toInt())
            }
            start = -1; r = 0; g = 0; bl = 0; n = 0
        }
        for (x in 0 until w) {
            var inked = false
            for (y in 0 until h) {
                val p = pixels[y * w + x]
                if ((luma(p) - background[x]) * sign >= INK_CONTRAST) {
                    r += red(p); g += green(p); bl += blue(p); n++
                    inked = true
                }
            }
            if (inked) {
                if (start < 0) start = x
            } else {
                close(x - 1)
            }
        }
        close(w - 1)
        return runs
    }

    /**
     * The original's edge and weight, from each row's runs of ink: what lies
     * just either side of a run is outline if it is clearly darker than the
     * panel (lighter, for dark ink), panel otherwise; the runs' typical
     * length is the strokes' width.
     */
    fun strokesOf(frame: FramePixels, box: TextBounds, lighterInk: Boolean): Strokes {
        val w = box.width
        val h = box.height
        if (w <= 2 * OUTLINE_PROBE_PX || h <= 0) return Strokes(null, null)
        val sign = if (lighterInk) 1.0 else -1.0
        val above = (box.top - 2).coerceAtLeast(0)
        val below = (box.bottom + 1).coerceAtMost(frame.height - 1)
        val outsideTop = IntArray(w).also { frame.getPixels(it, 0, w, box.left, above, w, 1) }
        val outsideBottom = IntArray(w).also { frame.getPixels(it, 0, w, box.left, below, w, 1) }
        val panel = DoubleArray(w) { (luma(outsideTop[it]) + luma(outsideBottom[it])) / 2 }
        val row = IntArray(w)
        val runs = mutableListOf<Int>()
        val edge = mutableListOf<Int>()
        var probes = 0
        for (y in box.top until box.bottom step 2) {
            frame.getPixels(row, 0, w, box.left, y, w, 1)
            fun ink(x: Int) = (luma(row[x]) - panel[x]) * sign >= INK_CONTRAST
            var x = 0
            while (x < w) {
                if (!ink(x)) { x++; continue }
                val start = x
                while (x < w && ink(x)) x++
                runs += x - start
                for (probe in listOf(start - OUTLINE_PROBE_PX, x - 1 + OUTLINE_PROBE_PX)) {
                    if (probe !in 0 until w) continue
                    probes++
                    if ((panel[probe] - luma(row[probe])) * sign >= OUTLINE_CONTRAST) edge += row[probe]
                }
            }
        }
        if (runs.isEmpty()) return Strokes(null, null)
        runs.sort()
        // The mean of the middle half, not the middle run: runs are whole
        // pixels, and at 40 px type the middle one was 5 on one line and 6 on
        // the next — 0.125 or 0.15 of the type, either side of bold, and a
        // chat's bubbles came out in two weights.
        val middleHalf = runs.subList(runs.size / 4, (runs.size * 3 / 4).coerceAtLeast(runs.size / 4 + 1))
        val weight = middleHalf.average().toFloat()
        val outline = if (probes > 0 && edge.size * 2 >= probes) {
            rgb(
                edge.sumOf { red(it) } / edge.size,
                edge.sumOf { green(it) } / edge.size,
                edge.sumOf { blue(it) } / edge.size,
            )
        } else {
            null
        }
        return Strokes(outline, weight)
    }

    fun sameRow(a: TextBounds, b: TextBounds): Boolean =
        minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) > minOf(a.height, b.height) / 2

    /**
     * [box] grown out to the ink just beyond it, by at most [reach] on each
     * side and along the row no further than [leftLimit] and [rightLimit]: a row or column still holding light-on-dark (or dark-on-light)
     * strokes past the edge is part of the line. Stops at the first clear
     * one, so a plain panel adds nothing.
     */
    fun inkExtent(
        frame: FramePixels,
        box: TextBounds,
        reach: Int,
        leftLimit: Int,
        rightLimit: Int,
        gapLines: Double = 1.0 / 3,
    ): TextBounds {
        if (box.width <= 0 || box.height <= 0) return box
        fun inked(pixels: IntArray, lighter: Boolean): Boolean {
            val lumas = pixels.map { luma(it) }
            val bg = lumas.sorted()[lumas.size / 2]
            val ink = lumas.map { (if (lighter) it - bg else bg - it) >= INK_CONTRAST }
            // Not what runs in from either end: that is another panel the
            // line's own one stops at, not type. White type in a blue bubble
            // it nearly fills — MomoTalk's 「それはよかった」 — has the white
            // page at the bubble's rounded corners and past its edges, and
            // the patch grew over the whole bubble and painted it white.
            val from = ink.indexOfFirst { !it }.let { if (it < 0) return false else it }
            val to = ink.indexOfLast { !it }
            return (from..to).count { ink[it] } >= EXTENT_MIN_INK
        }
        fun row(y: Int) = IntArray(box.width).also { frame.getPixels(it, 0, box.width, box.left, y, box.width, 1) }
        // Light ink on a dark panel: the middle row's middle value is panel.
        val middle = row(box.top + box.height / 2).map { luma(it) }.sorted()
        val lighter = middle[middle.size / 2] < MID_LUMA
        fun inked(pixels: IntArray) = inked(pixels, lighter)
        fun column(x: Int, top: Int, bottom: Int) =
            IntArray(bottom - top).also { frame.getPixels(it, 0, 1, x, top, 1, bottom - top) }

        // Up across a small gap too: ruby over the line belongs to it, and
        // left standing it read as stray Japanese above the translation. Not
        // as far as the name row, which sits a whole line above.
        var top = box.top
        var y = box.top - 1
        while (y >= 0 && box.top - y <= (reach * RUBY_REACH).toInt()) {
            if (inked(row(y))) top = y else if (top - y > reach / RUBY_GAP_DIVISOR) break
            y--
        }
        var bottom = box.bottom
        while (bottom < frame.height && bottom - box.bottom < reach && inked(row(bottom))) bottom++
        // Along the line, across a gap as well: a 「！」 or 「……」 the recogniser
        // left out stands a little apart from the rest, and missed, the
        // patch's edge was sampled on it and drew a white bar.
        // A third of the type: a name and its affiliation stand further apart.
        val gap = (reach * gapLines).toInt()
        var left = box.left
        // Room left for the patch's edge to be sampled on panel, inside the limits.
        val edge = SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS
        var x = box.left - 1
        while (x >= leftLimit + edge && box.left - x <= reach * ALONG_REACH) {
            if (inked(column(x, top, bottom))) left = x else if (left - x > gap) break
            x--
        }
        // Further along than up or down: a 「――」 the recogniser dropped ran
        // 300 px past the end of the line and was left standing.
        var right = box.right
        x = box.right
        while (x < rightLimit - edge && x - box.right < reach * ALONG_REACH) {
            if (inked(column(x, top, bottom))) right = x + 1 else if (x - right >= gap) break
            x++
        }
        // And in from an end the box took in with nothing on it: a left
        // bubble's 「いえ」 had its box start on the bubble's tail and margin,
        // half a character before its words, and the translation was set
        // from there, out past the bubble's edge. Only half a character or
        // more of nothing; a box's usual slack is left alone. And only nothing
        // plain: on a story's translucent box over the art, type the column
        // test did not see stood in what would have been cut.
        fun bare(x: Int): Boolean {
            val c = column(x, top, bottom).map { luma(it) }
            val mid = c.sorted()[c.size / 2]
            // A pixel or two off is the bubble's edge where its tail joins,
            // not type: the shortest stroke crosses more rows than that.
            return c.count { abs(it - mid) > BARE_LUMA } <= BARE_STRAY_PX
        }
        if (left == box.left) {
            var empty = 0
            while (empty < box.width / 3 && bare(box.left + empty)) empty++
            if (empty >= reach / 2) left = box.left + empty - SHRINK_SLACK_PX
        }
        if (right == box.right) {
            var empty = 0
            while (empty < box.width / 3 && bare(box.right - 1 - empty)) empty++
            if (empty >= reach / 2) right = box.right - empty + SHRINK_SLACK_PX
        }
        return TextBounds(left, top, right, bottom, box.space)
    }

    /** How many columns after [box], up to [most] and [limit], are the plain panel at its end. */
    fun plainAfter(frame: FramePixels, box: TextBounds, limit: Int, most: Int, leftward: Boolean = false): Int {
        val h = box.bottom - box.top
        if (h <= 0 || box.top < 0 || box.bottom > frame.height) return 0
        val column = IntArray(h)
        fun plain(x: Int): Double? {
            frame.getPixels(column, 0, 1, x, box.top, 1, h)
            val lumas = column.map { luma(it) }
            val mid = lumas.sorted()[h / 2]
            return if (lumas.any { abs(it - mid) > BARE_LUMA }) null else mid
        }
        // Columns going out from the box: right of it, or left of it.
        fun at(n: Int) = if (leftward) box.left - 1 - n else box.right + n
        fun inside(n: Int) = if (leftward) at(n) - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS > limit else at(n) + SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS < limit
        // From the first plain column: the box can end on the last glyph's
        // softened edge, and 「先生!」 got no room at all past its 「!」.
        var start = 0
        while (start < LEAD_IN_PX && inside(start) && plain(at(start)) == null) start++
        val reference = if (inside(start)) plain(at(start)) ?: return 0 else return 0
        var n = start
        while (n < most && inside(n)) {
            val mid = plain(at(n)) ?: break
            if (abs(mid - reference) > BARE_LUMA) break
            n++
        }
        // Room for the patch's own edge to be sampled still on the panel.
        return (n - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS).coerceAtLeast(0)
    }

    /**
     * The colour of the line's ink: its strongest pixels on the line's own
     * side of the backdrop. The mean of all of them came out grey — a glyph's
     * anti-aliased edge and a letter still fading in pull it toward the panel.
     */
    fun inkColour(frame: FramePixels, box: TextBounds, lighterInk: Boolean?): Int? {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val column = DoubleArray(h)
        var lighter = 0
        var darker = 0
        val light = mutableListOf<Int>()
        val dark = mutableListOf<Int>()
        for (x in 0 until w step 2) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            for (y in 0 until h) {
                val d = column[y] - bg
                if (d >= INK_CONTRAST) { lighter++; light += pixels[y * w + x] }
                else if (-d >= INK_CONTRAST) { darker++; dark += pixels[y * w + x] }
            }
        }
        val useLight = lighterInk ?: (lighter >= darker)
        val ink = if (useLight) light else dark
        if (ink.isEmpty()) return null
        val sorted = ink.sortedBy { luma(it) }
        val core = if (useLight) sorted.takeLast(max(1, sorted.size * INK_CORE_PERCENT / 100))
        else sorted.take(max(1, sorted.size * INK_CORE_PERCENT / 100))
        return rgb(
            core.sumOf { red(it) } / core.size,
            core.sumOf { green(it) } / core.size,
            core.sumOf { blue(it) } / core.size,
        )
    }

    /**
     * The line's own ink and a second one, when a line with figures has two:
     * a skill's description sets its figures in blue among grey words, and
     * its translation was set in one bluish grey throughout. The ink pixels
     * are split in two by colour; two inks far apart, the lesser a fair share
     * of the ink, are the words' and the figures'. Null for one ink.
     */
    fun twoInks(frame: FramePixels, box: TextBounds, lighterInk: Boolean?): Pair<Int, Int>? {
        val w = box.width
        val h = box.height
        if (w <= 0 || h <= 0 || box.left < 0 || box.top < 0 || box.right > frame.width || box.bottom > frame.height) return null
        val pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val column = DoubleArray(h)
        val ink = mutableListOf<Int>()
        for (x in 0 until w step 2) {
            for (y in 0 until h) column[y] = luma(pixels[y * w + x])
            val bg = column.sortedArray()[h / 2]
            for (y in 0 until h) {
                val d = column[y] - bg
                if ((lighterInk == true && d >= INK_CONTRAST) || (lighterInk != true && -d >= INK_CONTRAST)) ink += pixels[y * w + x]
            }
        }
        if (ink.size < MIN_TWO_INK_PIXELS) return null
        fun chroma(p: Int) = maxOf(red(p), green(p), blue(p)) - minOf(red(p), green(p), blue(p))
        // Two means, from the least and the most colourful pixel.
        var a = ink.minByOrNull(::chroma)!!
        var b = ink.maxByOrNull(::chroma)!!
        var inA: List<Int> = ink
        var inB = emptyList<Int>()
        repeat(6) {
            val (nearA, nearB) = ink.partition { colourDistance(it, a) <= colourDistance(it, b) }
            if (nearA.isEmpty() || nearB.isEmpty()) return null
            inA = nearA
            inB = nearB
            a = mean(nearA)
            b = mean(nearB)
        }
        val (words, figures) = if (inA.size >= inB.size) inA to inB else inB to inA
        if (figures.size * 100 < ink.size * MIN_ACCENT_PERCENT || colourDistance(mean(words), mean(figures)) <= ACCENT_DISTANCE) return null
        // Each ink by its strongest pixels, as [inkColour] takes one: the
        // mean of a glyph's anti-aliased edge is lighter than its stroke, and
        // set in it the words came out pale and were given an outline.
        fun core(pixels: List<Int>): Int {
            val sorted = pixels.sortedBy { luma(it) }
            val n = max(1, sorted.size * INK_CORE_PERCENT / 100)
            return mean(if (lighterInk == true) sorted.takeLast(n) else sorted.take(n))
        }
        return core(words) to core(figures)
    }
}
