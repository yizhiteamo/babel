package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLineStyleTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        TextBounds(left, top, right, bottom, CoordinateSpace.WINDOW)

    private fun grey(level: Int): Int {
        val c = level.coerceIn(0, 255)
        return GamePixels.rgb(c, c, c)
    }

    private fun frameOf(width: Int, height: Int, pixelAt: (x: Int, y: Int) -> Int): ArrayFramePixels {
        val data = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = pixelAt(x, y)
            }
        }
        return ArrayFramePixels(width, height, data)
    }

    @Test
    fun `typeHeight ignores detached ruby band bridges small internal row gaps and enforces floor`() {
        val target = box(20, 20, 180, 80) // height = 60, floor = (60 * 0.67).toInt() = 40
        val withRuby = frameOf(200, 100) { x, y ->
            when {
                // Detached ruby band at y in 22..27, separated by 4 blank rows (28..31 > TYPE_ROW_GAP = 2).
                y in 22..27 && x in 40 until 60 -> grey(240)
                // Main glyph band y in 32..73 (42 rows) with a 2-row internal gap at 50..51.
                y in 32..73 && y !in 50..51 && x in 30 until 150 -> grey(240)
                else -> grey(30)
            }
        }
        assertEquals(42, GameLineStyle.typeHeight(withRuby, target))

        // Narrow ink band (20 rows) is clamped up to MIN_TYPE_OF_BOX floor (40).
        val thinBand = frameOf(200, 100) { x, y ->
            if (y in 40 until 60 && x in 30 until 150) grey(240) else grey(30)
        }
        assertEquals(40, GameLineStyle.typeHeight(thinBand, target))

        // Blank panel with zero ink falls back to the full box height.
        val blank = frameOf(200, 100) { _, _ -> grey(30) }
        assertEquals(60, GameLineStyle.typeHeight(blank, target))
    }

    @Test
    fun `inkRuns auto-detects ink polarity and extracts contiguous column runs with mean RGB`() {
        val nameColour = GamePixels.rgb(240, 240, 245)
        val affiliationColour = GamePixels.rgb(80, 210, 250)
        val target = box(20, 10, 120, 50)

        val lightOnDark = frameOf(160, 60) { x, y ->
            when {
                x in 30..49 && y in 20..29 -> nameColour
                // Thin dark shadow row on the opposite side of background must be ignored when lighterInk wins.
                x in 30..49 && y == 19 -> grey(0)
                x in 65..84 && y in 20..29 -> affiliationColour
                else -> grey(35)
            }
        }
        val runs = GameLineStyle.inkRuns(lightOnDark, target)
        assertEquals(2, runs.size)
        assertEquals(GameLayout.InkRun(30, 49, 240, 240, 245, 200), runs[0])
        assertEquals(GameLayout.InkRun(65, 84, 80, 210, 250, 200), runs[1])

        val darkInk = GamePixels.rgb(25, 35, 50)
        val darkOnLight = frameOf(160, 60) { x, y ->
            if (x in 40..54 && y in 22..29) darkInk else grey(235)
        }
        val darkRuns = GameLineStyle.inkRuns(darkOnLight, target)
        assertEquals(listOf(GameLayout.InkRun(40, 54, 25, 35, 50, 120)), darkRuns)
    }

    @Test
    fun `strokesOf averages middle-half stroke widths and detects outline colour only when present`() {
        val outline = GamePixels.rgb(15, 25, 45)
        val stroke = GamePixels.rgb(240, 240, 240)
        val panel = grey(100)
        val target = box(20, 20, 140, 60)

        val outlined = frameOf(180, 80) { x, y ->
            if (y !in 24 until 56) {
                panel
            } else {
                when (x) {
                    // 5-px stroke at 30..34 and 6-px stroke at 60..65 (relative 10..14 and 40..45).
                    in 30..34, in 60..65 -> stroke
                    // Outline probes sit OUTLINE_PROBE_PX (2) outside start and end of each run.
                    28, 36, 58, 67 -> outline
                    else -> panel
                }
            }
        }
        val withOutline = GameLineStyle.strokesOf(outlined, target, lighterInk = true)
        assertEquals(5.5f, withOutline.weight)
        assertEquals(outline, withOutline.outline)

        val unoutlined = frameOf(180, 80) { x, y ->
            if (y in 24 until 56 && (x in 30..34 || x in 60..65)) stroke else panel
        }
        val withoutOutline = GameLineStyle.strokesOf(unoutlined, target, lighterInk = true)
        assertEquals(5.5f, withoutOutline.weight)
        assertNull(withoutOutline.outline)
    }

    @Test
    fun `inkColour and twoInks sample core ink pixels and split figure accent from body words`() {
        val coreDark = GamePixels.rgb(35, 40, 50)
        val softEdge = GamePixels.rgb(150, 155, 165)
        val panel = grey(245)
        val singleBox = box(20, 10, 100, 50)

        val antiAliased = frameOf(140, 60) { x, y ->
            when {
                x in 30 until 80 && y in 22..25 -> coreDark // 4 rows core
                x in 30 until 80 && y in 19..28 -> softEdge // 6 rows anti-aliased edge
                else -> panel
            }
        }
        // 3-arg overload takes darkest INK_CORE_PERCENT (30%), recovering exact core stroke colour.
        assertEquals(coreDark, GameLineStyle.inkColour(antiAliased, singleBox, lighterInk = false))
        assertNull(GameLineStyle.inkColour(frameOf(140, 60) { _, _ -> panel }, singleBox, lighterInk = false))

        // 5-arg overload returns mean [r, g, b] across all inked pixels, or null when blank.
        val fiveArg = GameLineStyle.inkColour(
            frameOf(80, 40) { x, y -> if (x in 15 until 35 && y in 15 until 22) GamePixels.rgb(200, 100, 50) else grey(20) },
            10,
            10,
            40,
            30,
        )
        assertNotNull(fiveArg)
        assertEquals(200.0, fiveArg[0], 0.01)
        assertEquals(100.0, fiveArg[1], 0.01)
        assertEquals(50.0, fiveArg[2], 0.01)
        assertNull(GameLineStyle.inkColour(frameOf(80, 40) { _, _ -> grey(20) }, 10, 10, 40, 30))

        // twoInks separates majority grey body ink from vivid blue figure ink.
        val bodyGrey = GamePixels.rgb(75, 78, 84)
        val figureBlue = GamePixels.rgb(25, 135, 245)
        val dualBox = box(10, 10, 210, 50)
        val dualFrame = frameOf(230, 60) { x, y ->
            when {
                x in 20 until 140 && y in 20..29 -> bodyGrey // 60 sampled columns * 10 rows = 600 px
                x in 150 until 190 && y in 20..29 -> figureBlue // 20 sampled columns * 10 rows = 200 px (25%)
                else -> panel
            }
        }
        assertEquals(bodyGrey to figureBlue, GameLineStyle.twoInks(dualFrame, dualBox, lighterInk = false))

        // Single-colour line returns null from twoInks.
        val monoFrame = frameOf(230, 60) { x, y ->
            if (x in 20 until 180 && y in 20..29) bodyGrey else panel
        }
        assertNull(GameLineStyle.twoInks(monoFrame, dualBox, lighterInk = false))
    }

    @Test
    fun `inkExtent grows over ruby and punctuation within limits shrinks bare slack and plainAfter measures room`() {
        val panel = grey(30)
        val ink = grey(235)
        val initial = box(80, 50, 180, 80)

        val rubyAndDots = frameOf(300, 120) { x, y ->
            when {
                // Ruby rows at y in 40..45 (4 empty rows 46..49 <= reach / RUBY_GAP_DIVISOR = 7).
                y in 40..45 && x in 100..120 -> ink
                // Main line glyph strokes inside box (< 50% of any row so row median stays panel).
                y in 56..74 && x in 82..177 && (x % 6) < 2 -> ink
                // Trailing ellipsis across a 6-px gap (180..185 <= gap = 10) at x in 186..195.
                y in 62..68 && x in 186..195 -> ink
                else -> panel
            }
        }
        val grown = GameLineStyle.inkExtent(rubyAndDots, initial, reach = 30, leftLimit = 0, rightLimit = 300)
        assertEquals(box(80, 40, 196, 80), grown)

        // When rightLimit leaves no room beyond box.right + edge (edge = 5), horizontal growth stops.
        val clamped = GameLineStyle.inkExtent(rubyAndDots, initial, reach = 30, leftLimit = 0, rightLimit = 185)
        assertEquals(box(80, 40, 180, 80), clamped)

        // Bare slack of >= reach / 2 (15 px) at the box's start is shrunk back, keeping SHRINK_SLACK_PX (2).
        val slackBox = box(60, 50, 180, 80)
        val bareStart = frameOf(260, 120) { x, y ->
            if (y in 58..69 && x in 80..176 && (x - 80) % 6 < 2) ink else panel
        }
        val shrunk = GameLineStyle.inkExtent(bareStart, slackBox, reach = 30, leftLimit = 0, rightLimit = 260)
        assertEquals(box(78, 50, 180, 80), shrunk)

        // plainAfter skips softened glyph lead-in (< LEAD_IN_PX = 6) and leaves edge margin before a panel border.
        val roomBox = box(80, 50, 160, 80)
        val roomFrame = frameOf(260, 120) { x, y ->
            when {
                // Softened glyph edge for 3 columns past box.right (160..162) on a few rows.
                x in 160..162 && y in 60..66 -> grey(120)
                // Bright border at x = 200 and x = 40.
                x == 200 || x == 40 -> grey(210)
                else -> panel
            }
        }
        // Rightward: start = 3, plain runs through n = 39 (x = 199), stops at n = 40 (x = 200) -> 40 - 5 = 35.
        assertEquals(35, GameLineStyle.plainAfter(roomFrame, roomBox, limit = 250, most = 60))
        // Leftward: plain from n = 0 (x = 79) through n = 38 (x = 41), stops at n = 39 (x = 40) -> 39 - 5 = 34.
        assertEquals(34, GameLineStyle.plainAfter(roomFrame, roomBox, limit = 10, most = 60, leftward = true))
    }

    @Test
    fun `colouredMarkInside iconBefore bar inkedColumns and sameRow detect leading ornaments and row overlap`() {
        // Pale cyan bar on white panel: luma ~223 vs 250 (< INK_CONTRAST 28), colour distance ~95 (> MARK_COLOUR 90).
        val whitePanel = GamePixels.rgb(250, 250, 250)
        val paleCyan = GamePixels.rgb(155, 252, 255)
        val darkGlyph = grey(40)
        val markBox = box(20, 20, 140, 60)
        val markFrame = frameOf(180, 80) { x, y ->
            when {
                x in 22..25 && y in 24..55 -> paleCyan
                x in 35..90 && y in 28..50 -> darkGlyph
                else -> whitePanel
            }
        }
        // Mark ends at x + 1 = 26, plus SIDE_MARGIN_PX (2) = 28.
        assertEquals(28, GameLineStyle.colouredMarkInside(markFrame, markBox))

        // iconBefore finds a multi-column differently-coloured badge (>= MIN_ICON_PX = 6) before the box.
        val nameBox = box(60, 20, 160, 60)
        val pinkBadge = GamePixels.rgb(245, 100, 150)
        val badgeFrame = frameOf(200, 80) { x, y ->
            when {
                x in 44..52 && y in 30..44 -> pinkBadge
                x in 62..120 && y in 30..44 -> grey(240)
                else -> grey(30)
            }
        }
        assertEquals(53, GameLineStyle.iconBefore(badgeFrame, nameBox, typeHeight = 36))
        assertEquals(9, GameLineStyle.inkedColumns(badgeFrame, 40, 20, 58, 60))

        // Narrow 2-column border before the box is rejected (< MIN_ICON_PX).
        val borderFrame = frameOf(200, 80) { x, y ->
            when {
                x in 51..52 && y in 30..44 -> pinkBadge
                x in 62..120 && y in 30..44 -> grey(240)
                else -> grey(30)
            }
        }
        assertNull(GameLineStyle.iconBefore(borderFrame, nameBox, typeHeight = 36))

        // bar detects a narrow leading bar (<= h / 4) followed by >= BAR_GAP_PX (4) blank columns
        // when >= h / 2 pixels in each bar column stand >= INK_CONTRAST from the column's median.
        val barBox = box(20, 20, 140, 60) // h = 40 -> max bar width 10
        val barFrame = frameOf(160, 80) { x, y ->
            when {
                x in 22..25 && y in 30..49 -> grey(240) // 20 rows (sorted()[20] = 240, 20 panel rows differ -> 20 * 2 >= 40)
                x in 32..90 && y in 30..44 -> grey(240) // 6 blank columns 26..31 before main text
                else -> grey(30)
            }
        }
        assertEquals(12, GameLineStyle.bar(barFrame, barBox))

        // Existing behaviour: a flat two-tone bar taller than h / 2 flips the column median to the bar's own luma,
        // leaving < h / 2 panel pixels differing from it, so inked(x) is false and bar() returns null.
        val tallTwoToneBar = frameOf(160, 80) { x, y ->
            when {
                x in 22..25 && y in 24..55 -> grey(240) // 32 of 40 rows
                x in 32..90 && y in 30..44 -> grey(240)
                else -> grey(30)
            }
        }
        assertNull(GameLineStyle.bar(tallTwoToneBar, barBox))

        // bar safely returns null when an inked run reaches the right edge of the frame.
        val edgeRunFrame = frameOf(140, 80) { x, y ->
            if (x in 135 until 140 && y in 25..50) grey(240) else grey(30)
        }
        assertNull(GameLineStyle.bar(edgeRunFrame, box(120, 20, 140, 60)))

        // sameRow requires strict vertical overlap exceeding half of the shorter box's height.
        assertTrue(GameLineStyle.sameRow(box(10, 20, 60, 60), box(80, 30, 140, 70)))
        assertFalse(GameLineStyle.sameRow(box(10, 20, 60, 60), box(80, 45, 140, 85)))
    }
}
