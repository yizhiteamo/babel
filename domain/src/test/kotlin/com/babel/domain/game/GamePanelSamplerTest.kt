package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GamePanelSamplerTest {

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
    fun `backdropError distinguishes plain or graded panels from busy art and recovers bubble overhangs`() {
        val w = 240
        val h = 120
        val target = box(40, 40, 200, 80)

        val plain = frameOf(w, h) { _, _ -> grey(50) }
        assertEquals(0.0, GamePanelSampler.backdropError(plain, w, h, target))

        val graded = frameOf(w, h) { x, _ -> grey(40 + x * 80 / w) }
        assertTrue(GamePanelSampler.backdropError(graded, w, h, target) < 1.5)

        val noisyArt = frameOf(w, h) { x, _ -> if ((x / 4) % 2 == 0) grey(20) else grey(220) }
        assertTrue(GamePanelSampler.backdropError(noisyArt, w, h, target) > 60.0)

        // Bubble on x in 45..194 (luma 50) over a white page (luma 240).
        val bubbleOnPage = frameOf(w, h) { x, _ -> if (x in 45 until 195) grey(50) else grey(240) }
        val oneEndOver = box(50, 40, 198, 80)
        val bothEndsOver = box(42, 40, 198, 80)
        assertTrue(GamePanelSampler.backdropError(bubbleOnPage, w, h, oneEndOver) < GameTextFilter.MAX_BACKDROP_ERROR)
        assertTrue(GamePanelSampler.backdropError(bubbleOnPage, w, h, bothEndsOver) < GameTextFilter.MAX_BACKDROP_ERROR)
    }

    @Test
    fun `onItsPanel trims pointed bubble tail columns off either end`() {
        val frame = frameOf(200, 100) { x, y ->
            when {
                // Main bubble body at x in 52..137, y in 20..80.
                x in 52 until 138 && y in 20 until 80 -> grey(40)
                // Pointed tails at left (40..51) and right (138..149) only at mid-height y in 45..55.
                x in 40 until 150 && y in 45 until 55 -> grey(40)
                else -> grey(240)
            }
        }

        val trimmed = GamePanelSampler.onItsPanel(frame, box(40, 30, 150, 70))
        assertEquals(box(52, 30, 138, 70), trimmed)
    }

    @Test
    fun `rowGradient repairs stray edge samples falls back on button borders and supports fromStartOnly`() {
        val panel = grey(60)
        val border = grey(190)
        val stray = grey(245)
        val target = box(40, 30, 160, 60)

        val frame = frameOf(220, 100) { x, y ->
            when {
                // One stray white row on the left edge sample columns (x in 35..37).
                x in 35..37 && y == 40 -> stray
                // Bright button border along the entire right edge sample columns (x in 162..164).
                x in 162..164 -> border
                else -> panel
            }
        }

        val gradient = assertNotNull(GamePanelSampler.rowGradient(frame, target, margin = 2, wantedRowMargin = 4))
        assertEquals(38, gradient.rows)
        assertTrue(gradient.left.all { it == panel })
        // Right end stood on the bright border, so fit() falls back to the row's inside panel colour.
        assertTrue(gradient.right.all { it == panel })

        val overArtFrame = frameOf(220, 100) { x, y ->
            if (y in 20 until 70 && x in 40 until 160) grey(45) else grey(230)
        }
        val overArt = assertNotNull(
            GamePanelSampler.rowGradient(overArtFrame, target, margin = 2, wantedRowMargin = 12, fromStartOnly = true),
        )
        assertEquals(12, overArt.rowMarginPx)
        assertEquals(overArt.left, overArt.right)
    }

    @Test
    fun `repainted requires both above and below panel edges to change and backdropOf picks median edge colour`() {
        val base = frameOf(120, 80) { _, _ -> grey(230) }
        val lowerMovedOnly = frameOf(120, 80) { _, y -> if (y >= 50) grey(120) else grey(230) }
        val shadedRow = frameOf(120, 80) { _, _ -> grey(190) }
        val lineBox = box(20, 20, 100, 50)

        val was = GamePanelSampler.panelAround(base, lineBox)
        assertFalse(GamePanelSampler.repainted(was, GamePanelSampler.panelAround(lowerMovedOnly, lineBox)))
        assertTrue(GamePanelSampler.repainted(was, GamePanelSampler.panelAround(shadedRow, lineBox)))

        val piece = frameOf(20, 10) { x, y ->
            if ((x == 0 || x == 19) && y == 0) grey(240) else grey(45)
        }
        assertEquals(grey(45), GamePanelSampler.backdropOf(piece))
    }

    @Test
    fun `panelsOf checks vertical wrap panel horizontal shadow edges and chat page boundaries`() {
        val above = GameLine("上の行", box(40, 20, 200, 50), backdropError = 1.0, typeHeight = 28)
        val below = GameLine("下の行", box(40, 62, 180, 92), backdropError = 1.0, typeHeight = 28)

        val samePanel = GamePanelSampler.panelsOf(frameOf(260, 120) { _, _ -> grey(40) })
        val splitByPage = GamePanelSampler.panelsOf(
            frameOf(260, 120) { _, y -> if (y in 52 until 61) grey(220) else grey(40) },
        )
        assertTrue(samePanel.joined(above, below))
        assertFalse(splitByPage.joined(above, below))

        val leftLabel = GameLine("左", box(20, 40, 80, 70), backdropError = 1.0)
        val rightLabel = GameLine("右", box(140, 40, 200, 70), backdropError = 1.0)
        val cleanRow = GamePanelSampler.panelsOf(frameOf(260, 120) { _, _ -> grey(220) })
        val borderedRow = GamePanelSampler.panelsOf(
            frameOf(260, 120) { x, _ -> if (x in 105 until 115) grey(30) else grey(220) },
        )
        assertTrue(cleanRow.beside(leftLabel, rightLabel))
        assertFalse(borderedRow.beside(leftLabel, rightLabel))

        val reply = GameLine("返信", box(150, 40, 210, 70), backdropError = 1.0)
        val onChatPage = GamePanelSampler.panelsOf(
            frameOf(260, 120) { x, _ -> if (x in 150 until 210) grey(195) else grey(230) },
        )
        val pastWindowEdge = GamePanelSampler.panelsOf(
            frameOf(260, 120) { x, _ -> if (x >= 215) grey(25) else grey(230) },
        )
        assertTrue(onChatPage.across(reply, fromX = 40))
        assertFalse(pastWindowEdge.across(reply, fromX = 40))
    }

    @Test
    fun `onBlank expands to clean panel or returns null over busy art and changedBoxes merges or widens to band`() {
        val w = 320
        val h = 145
        // Busy alternating texture inside (96..136, 48..72), surrounded by plain panel outside.
        val islandFrame = frameOf(w, h) { x, y ->
            if (x in 96 until 136 && y in 48 until 72 && ((x / 2 + y / 2) % 2 == 0)) grey(20) else grey(220)
        }
        val expanded = assertNotNull(GamePanelSampler.onBlank(islandFrame, intArrayOf(100, 50, 132, 70)))
        assertTrue(expanded[0] <= 96 && expanded[1] <= 48 && expanded[2] >= 136 && expanded[3] >= 72)

        val allBusy = frameOf(w, h) { x, y -> if ((x / 2 + y / 2) % 2 == 0) grey(20) else grey(220) }
        assertNull(GamePanelSampler.onBlank(allBusy, intArrayOf(100, 50, 132, 70)))

        val baseGrid = IntArray(GameFrameLook.GRID_COLUMNS * GameFrameLook.GRID_ROWS) { 100 }
        val twoCloseRegions = baseGrid.copyOf().also {
            it[10 * GameFrameLook.GRID_COLUMNS + 20] = 200
            it[10 * GameFrameLook.GRID_COLUMNS + 25] = 200
        }
        val plainFrame = frameOf(w, h) { _, _ -> grey(220) }
        val spanningLine = listOf(GameLine("跨ぐ行", box(105, 48, 125, 58), backdropError = 1.0))
        val mergedBoxes = assertNotNull(GamePanelSampler.changedBoxes(plainFrame, baseGrid, spanningLine, twoCloseRegions))
        assertEquals(1, mergedBoxes.size)

        val oneRegion = baseGrid.copyOf().also { it[10 * GameFrameLook.GRID_COLUMNS + 20] = 200 }
        val bandFallback = assertNotNull(GamePanelSampler.changedBoxes(allBusy, baseGrid, emptyList(), oneRegion))
        assertEquals(1, bandFallback.size)
        assertEquals(0, bandFallback[0][0])
        assertEquals(w, bandFallback[0][2])
    }
}
