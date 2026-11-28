package com.babel.domain.game

import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation
import com.babel.domain.game.GameLineStyle.SIDE_MARGIN_PX
import com.babel.domain.game.GameLineStyle.colouredMarkInside
import com.babel.domain.game.GameLineStyle.iconBefore
import com.babel.domain.game.GameLineStyle.inkColour
import com.babel.domain.game.GameLineStyle.inkExtent
import com.babel.domain.game.GameLineStyle.plainAfter
import com.babel.domain.game.GameLineStyle.sameRow
import com.babel.domain.game.GameLineStyle.strokesOf
import com.babel.domain.game.GameLineStyle.twoInks
import com.babel.domain.game.GamePanelSampler.EDGE_SAMPLE_COLUMNS
import com.babel.domain.game.GamePanelSampler.MID_LUMA
import com.babel.domain.game.GamePanelSampler.rowGradient
import com.babel.domain.game.GamePanelSampler.sidePanel
import com.babel.domain.game.GamePixels.colourDistance
import com.babel.domain.game.GamePixels.luma
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cross-frame style stabilization memory for game lines: per-kind stroke
 * weight (`labelWeights` / `lineWeights`), grouped glyph size (`typeSizes`),
 * and multi-label row style (`rowStyles`), plus full per-line [SourceStyle]
 * construction ([lookOf]).
 *
 * Not thread-safe: callers must only read, update, or [clear] this instance
 * while holding `GameTextSource.lock`.
 */
class GameStyleMemory {

    /** One row of labels' box and weight, as first set; see `elementOf` in `GameTextSource.readOne`. */
    private class RowStyle(val top: Int, val bottom: Int, val weight: Float?, val ink: Int?)
    private val rowStyles = ArrayDeque<RowStyle>()

    /**
     * One weight for a kind of line, not one per line: measured per line, the
     * strokes of one game's dialogue came out 0.13 on one line and 0.16 on the
     * next, and consecutive lines of one box were set in two weights. Names
     * and dialogue each take the middle of their first measurements, and it
     * then stays: a running mean drifted across a step in weight in the
     * middle of a chat, and the lines settled after it came out bolder.
     */
    private val labelWeights = mutableListOf<Float>()
    private val lineWeights = mutableListOf<Float>()

    fun weightOf(label: Boolean): Float? =
        (if (label) labelWeights else lineWeights).takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    /**
     * One size for one size of type, not one per line: the height of a line's
     * ink depends on its letters — a chat's 「はい」 measured 37 px, its
     * bubbles of kanji 41, in one font of one size — and each translation was
     * set at its own line's measure. Sizes within [SAME_TYPE_RATIO] of a size
     * already seen are that size, the tallest measured, which is the type's.
     * Names and lines apart: a game sets its names smaller or larger.
     */
    private class TypeSize(val speaker: Boolean, var smallest: Int, var tallest: Int) {
        /** The type's size measured along the line, one per line that allowed it. */
        val ems = ArrayDeque<Double>()
    }
    private val typeSizes = mutableListOf<TypeSize>()

    /**
     * The size to set a line of this kind at, as the glyph height the
     * renderer sizes from. Taken from the type's width where the line's
     * characters allow it: ink height had a chat's bubbles set 13% larger
     * than the original — 41 px per character across, 46.6 px set — and the
     * short ones (「先生」 → 「老师」) then no longer fitted and were shrunk.
     */
    fun steadySize(speaker: Boolean, measured: Int, em: Double?): Int {
        // Grouped by the ink's height, within the ratio of the smallest and
        // the tallest alike, so a size cannot creep up into the next one.
        var size = typeSizes.firstOrNull { t ->
            t.speaker == speaker && min(t.smallest, measured).toDouble() / max(t.tallest, measured) >= SAME_TYPE_RATIO
        }
        if (size == null) {
            size = TypeSize(speaker, measured, measured)
            typeSizes += size
        }
        size.smallest = min(size.smallest, measured)
        size.tallest = max(size.tallest, measured)
        if (em != null) {
            size.ems.addLast(em)
            while (size.ems.size > MAX_EM_SAMPLES) size.ems.removeFirst()
        }
        if (size.ems.isEmpty()) return size.tallest
        val typical = size.ems.sorted()[size.ems.size / 2]
        return (typical * GLYPH_OF_EM).toInt()
    }

    /**
     * The type's size from the line's width: full-width characters are one
     * size across, others about half. Null for a line too short to say, or
     * one whose reading does not fit its box (a misread, a dropped mark).
     */
    fun emOf(line: GameLine): Double? {
        val units = line.text.sumOf { c -> if (c == '　') 1.0 else if (c.isWhitespace()) 0.0 else if (c.code >= FULL_WIDTH_FROM || c in FULL_WIDTH_MARKS) 1.0 else HALF_WIDTH }
        // One line only: a wrapped utterance joined into one has the
        // characters of all its lines across the width of the longest.
        if (units < MIN_EM_UNITS || line.bounds.height > line.typeHeight * MAX_ONE_LINE_OF_TYPE) return null
        val em = line.bounds.width / units
        val fromHeight = line.typeHeight / GLYPH_OF_EM
        return em.takeIf { it in fromHeight * MIN_EM_OF_HEIGHT..fromHeight * MAX_EM_OF_HEIGHT }
    }

    fun steadyWeight(label: Boolean, measured: Float): Float {
        val samples = if (label) labelWeights else lineWeights
        if (samples.size < WEIGHT_SAMPLES) samples += measured
        return weightOf(label) ?: measured
    }

    fun withRowStyle(
        read0: GameLine,
        seen: Pair<Int, Int>?,
        conversational: Boolean,
        speaker: Boolean,
        inked: GameLine,
        look0: SourceStyle,
    ): SourceStyle {
        // Found again by its place even when the row is not all read on
        // this frame: a label settling alone, its mates held from the
        // frames before, came out bold beside them.
        val centre = seen?.let { (top, bottom) -> (top + bottom) / 2 } ?: ((read0.bounds.top + read0.bounds.bottom) / 2)
        val row = if (seen == null && (conversational || speaker || !GameLayout.isLabel(read0.text))) null else
            rowStyles.firstOrNull { abs((it.top + it.bottom) / 2 - centre) <= read0.typeHeight / 2 }
        if (seen != null && row == null) {
            // The box the ink took, not the recogniser's: the patch must
            // still cover the type.
            rowStyles.addFirst(RowStyle(inked.bounds.top, inked.bounds.bottom, look0.strokeWeight, look0.foregroundColor))
            while (rowStyles.size > MAX_ROW_STYLES) rowStyles.removeLast()
        }
        val style = row ?: rowStyles.firstOrNull().takeIf { seen != null }
        // And one ink, where the row's inks are one colour measured
        // twice: 「EXスキル」 on the selected card, under its glow, came
        // out a deeper blue than the three beside it.
        val ink = style?.ink?.takeIf { i -> look0.foregroundColor?.let { colourDistance(it, i) <= ROW_INK_DISTANCE } == true }
        return if (style == null) look0 else look0.copy(strokeWeight = style.weight ?: look0.strokeWeight, foregroundColor = ink ?: look0.foregroundColor)
    }

    /**
     * How the line looks, for drawing its translation in its place, and the
     * line with its bounds grown out to all of its ink: the ink's
     * colour, the type's height, and — on a panel plain enough to paint back —
     * the backdrop row by row, so the original can be erased
     * (`docs/features/v3-game-translation.md`, "erase and redraw").
     */
    fun lookOf(
        frame: FramePixels,
        recognised: GameLine,
        others: List<GameLine>,
        iconBoxes: List<TextBounds>,
        speaker: Boolean,
    ): Pair<GameLine, SourceStyle> {
        // The ink, not the recogniser's box, is what has to be covered: a
        // name read while it was still sliding in had a box missing its top,
        // and a line read without its opening 「……」 left the dots showing.
        // Never past halfway to the next piece on its row, though: a short
        // name's patch grew into the affiliation beside it and erased 「玄」.
        val b = recognised.bounds
        // On its own panel only: the dimmed list behind a pop-up stood on the
        // pop-up's rows, and its centred lines were set from the left.
        // Nor the pieces of the line itself: a wrapped block's own lines are
        // on its rows, and a centred pop-up's message was set from the left.
        val beside = (others + iconBoxes.map { GameLine("", it, 0.0) }).filter { it.bounds != b && sameRow(it.bounds, b) && !within(it.bounds, b) }
        val row = beside.filter { sidePanel(frame, it, recognised) }
        // Every piece on the row bounds the patch, on this panel or another:
        // a centred pop-up line's patch grew over the dimmed list behind it.
        val markEnd = colouredMarkInside(frame, b)
        val leftLimit = maxOf(
            beside.filter { it.bounds.right <= b.left }.maxOfOrNull { (it.bounds.right + b.left) / 2 } ?: 0,
            iconBefore(frame, b, recognised.typeHeight) ?: 0,
        )
        val rightLimit = beside.filter { it.bounds.left >= b.right }.minOfOrNull { (it.bounds.left + b.right) / 2 } ?: frame.width
        val extent = inkExtent(frame, b, recognised.typeHeight, leftLimit, rightLimit)
        // Centred on the screen — a choice, a banner — and set again centred,
        // over a box made even about the centre: the ink to one side reached
        // further than the other once a closing 」 stood apart, and was left
        // standing beside a translation set from the left.
        val mid = frame.width / 2
        // Not a line of the dialogue box, which runs from the box's margin
        // and can be centred by chance: a two-line utterance was set centred.
        val onScreenCentre = row.isEmpty() && extent.width < frame.width * MAX_CENTRED_WIDTH &&
            extent.left > frame.width * MIN_CENTRED_LEFT &&
            abs((extent.left + extent.right) / 2 - mid) <= frame.width * CENTRED_TOLERANCE
        // Or in the middle of a plain panel of its own: a reply button in a
        // chat, half the screen from the centre. Set from the left, its longer
        // translation had room on one side only and came out smaller than the
        // option above it.
        val rightLimitEdge = minOf(rightLimit - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS, frame.width)
        val leftLimitEdge = maxOf(leftLimit + SIDE_MARGIN_PX + EDGE_SAMPLE_COLUMNS, 0)
        // Nor a line starting where a name over it starts: dialogue under its
        // speaker. Under 「？？？」, over a figure's coat that ended either side
        // of it, 「お会いできて嬉しいです、先生。」 was taken for a button and
        // its translation set in from the margin.
        val underName = others.any { o ->
            o.bounds != b && GameLayout.speaksAbove(o.bounds, b) && abs(o.bounds.left - b.left) <= recognised.typeHeight
        }
        val panelMiddle = if (onScreenCentre || row.isNotEmpty() || underName) null else {
            val far = (recognised.typeHeight * PANEL_CENTRE_LOOK).toInt()
            val l = plainAfter(frame, extent, leftLimitEdge, far, leftward = true)
            val r = plainAfter(frame, extent, rightLimitEdge, far)
            // Both edges found: a button's panel ends either side. Plain as far
            // as was looked on both sides is an open panel — a story's dialogue
            // box, a short line in it — and a line there is set from the left:
            // centred, 「虚像已无影无踪…」 stood in the middle of the box.
            val reach = far - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS
            if (l >= recognised.typeHeight * PANEL_CENTRE_ROOM && r >= recognised.typeHeight * PANEL_CENTRE_ROOM &&
                abs(l - r) <= recognised.typeHeight && (l < reach || r < reach)
            ) {
                (extent.left + extent.right) / 2
            } else {
                null
            }
        }
        val centred = onScreenCentre || panelMiddle != null
        // Followed across wider gaps too: a choice's quotation marks stand a
        // character and a half off its words, and were left beside them.
        val wide = if (centred) inkExtent(frame, extent, recognised.typeHeight, leftLimit, rightLimit, gapLines = CENTRED_GAP_LINES) else extent
        val centre = panelMiddle ?: mid
        val half = max(centre - wide.left, wide.right - centre)
            .coerceAtMost(min(centre - leftLimit, rightLimit - centre).coerceAtLeast(max(centre - extent.left, extent.right - centre)))
        // Past the end of a line set from the left, the plain panel it stands
        // on is room for a translation a little wider than the original: in a
        // chat's bubble, 「打扫?」 for 「掃除?」 was a few pixels too wide for the
        // original's ink and was shrunk, smaller than every bubble around it.
        val most = (recognised.typeHeight * ROOM_OF_TYPE).toInt()
        val room = if (centred) 0 else plainAfter(frame, extent, minOf(rightLimit - SIDE_MARGIN_PX - EDGE_SAMPLE_COLUMNS, frame.width), most)
        // Centred, the same on both sides: a reply button's 「今天也在冒险中吗?」
        // was two characters wider than 「今日も冒険中?」 on a button with room to
        // spare, and set smaller than the option above it.
        val roomEach = if (!centred) 0 else {
            val probe = TextBounds(centre - half, wide.top, centre + half, wide.bottom, wide.space)
            minOf(
                plainAfter(frame, probe, rightLimitEdge, most),
                plainAfter(frame, probe, leftLimitEdge, most, leftward = true),
            )
        }
        val line = recognised.copy(
            bounds = if (centred) {
                TextBounds(centre - half - roomEach, wide.top, centre + half + roomEach, wide.bottom, wide.space)
            } else {
                TextBounds(max(extent.left, markEnd ?: 0), extent.top, extent.right + room, extent.bottom, extent.space)
            },
        )
        // Room for a line of type the original's height: a typeface's line is
        // taller than its glyphs' ink, and in the recogniser's tight box the
        // translation was shrunk to about 60% of the original to fit.
        val lineRoom = (line.typeHeight * TYPE_LINE_RATIO).toInt() - line.bounds.height
        val rowMargin = maxOf(MIN_ERASE_MARGIN_PX, line.typeHeight / ERASE_MARGIN_DIVISOR, lineRoom / 2 + 2)
        // Over art, painted from its start alone: the panel under a name is
        // where the name begins, and Fate/Grand Order's tab fades into the
        // picture before the end of a long name, where the far end's colour
        // drew a black block behind 「罗曼医生」.
        // With a piece beside it on its row — a name before its affiliation —
        // the gap between the two is still the tab, and both ends can be
        // sampled: painted from the start alone, the tab's fade along a long
        // name was lost and its place showed as a darker block.
        val onArt = line.backdropError > GameTextFilter.MAX_BACKDROP_ERROR && rightLimit >= frame.width
        val backdrop = if (line.backdropError <= MAX_ERASE_ERROR) {
            rowGradient(frame, line.bounds, SIDE_MARGIN_PX, rowMargin, fromStartOnly = onArt)
        } else {
            null
        }
        // Where the panel is known, the ink is whatever stands out of it: a
        // short piece can count more dark outline than light letter, and
        // measured, one cyan 風紀委員会 came back navy.
        val lighterInk = backdrop?.let { luma(it.colorAt(it.rows / 2, 0.5f)) < MID_LUMA }
        val strokes = strokesOf(frame, line.bounds, lighterInk ?: true)
        val single = inkColour(frame, line.bounds, lighterInk)
        val two = if (single != null && line.text.any(Char::isDigit)) twoInks(frame, line.bounds, lighterInk) else null
        return line to SourceStyle(
            foregroundColor = two?.first ?: single,
            accentColor = two?.second,
            orientation = TextOrientation.HORIZONTAL,
            glyphSizePx = steadySize(speaker, line.typeHeight, null),
            backdrop = backdrop,
            outlineColor = strokes.outline,
            // Unmeasured, the weight of its kind: left out, a line came out
            // regular among bold ones of the same type.
            strokeWeight = strokes.weight?.let { steadyWeight(speaker, it / line.typeHeight.coerceAtLeast(1)) }
                ?: weightOf(speaker),
            centred = centred,
        )
    }

    private fun within(inner: TextBounds, outer: TextBounds): Boolean =
        inner.left >= outer.left - 2 && inner.right <= outer.right + 2 && inner.top >= outer.top - 2 && inner.bottom <= outer.bottom + 2

    fun clear() {
        rowStyles.clear()
        labelWeights.clear()
        lineWeights.clear()
        typeSizes.clear()
    }

    companion object {
        const val MAX_ROW_STYLES = 16
        /** Inks of one row this close (0–441) are one ink: 466398 against 496f8f measured 22. */
        const val ROW_INK_DISTANCE = 45.0

        /**
         * The most backdrop error a line may have and still be erased: the
         * interpolation that measures it is the one that paints it back.
         */
        const val MAX_ERASE_ERROR = GameTextFilter.MAX_BACKDROP_ERROR * 3

        /**
         * A line of type against its glyphs' ink: text size 1/0.88 of it, and
         * a line of CJK type — the fallback font sets the line's height —
         * about 1.45 of that.
         */
        const val TYPE_LINE_RATIO = 1.45 / 0.88

        /** Clear space above and below the text, against the type's height; at least a few pixels. */
        const val ERASE_MARGIN_DIVISOR = 8
        const val MIN_ERASE_MARGIN_PX = 4

        /** A line whose middle is this close (of the width) to the screen's is centred, if not too wide. */
        const val CENTRED_TOLERANCE = 0.02
        const val MAX_CENTRED_WIDTH = 0.6
        const val MIN_CENTRED_LEFT = 0.2
        const val CENTRED_GAP_LINES = 2.0

        /** The renderer's glyph height against the type's size (`ErasedTextView`'s GLYPH_TO_TEXT_SIZE). */
        const val GLYPH_OF_EM = 0.88
        const val FULL_WIDTH_FROM = 0x2E80
        const val FULL_WIDTH_MARKS = "…‥―—"
        const val HALF_WIDTH = 0.55
        const val MIN_EM_UNITS = 2.0
        const val MIN_EM_OF_HEIGHT = 0.75
        const val MAX_EM_OF_HEIGHT = 1.15
        const val MAX_EM_SAMPLES = 32
        const val MAX_ONE_LINE_OF_TYPE = 1.6

        /** Type heights this close are one size of type, measured on different letters. */
        const val SAME_TYPE_RATIO = 0.86

        /** How many lines of a kind its weight is taken from. */
        const val WEIGHT_SAMPLES = 12

        /** How much plain panel past a line's end may be taken as room, against its type's height. */
        const val ROOM_OF_TYPE = 1.0

        /** How far, in type heights, plain panel is looked for either side, and how much each side needs to be the panel's middle. */
        const val PANEL_CENTRE_LOOK = 4.0
        const val PANEL_CENTRE_ROOM = 2.0
    }
}
