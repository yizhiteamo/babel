package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameFrameLookTest {

    private fun box(left: Int, top: Int, right: Int, bottom: Int) =
        TextBounds(left, top, right, bottom, CoordinateSpace.WINDOW)

    private fun frameOf(width: Int, height: Int, pixelAt: (x: Int, y: Int) -> Int): ArrayFramePixels {
        val data = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                data[y * width + x] = pixelAt(x, y)
            }
        }
        return ArrayFramePixels(width, height, data)
    }

    private fun grey(level: Int): Int {
        val c = level.coerceIn(0, 255)
        return GamePixels.rgb(c, c, c)
    }

    @Test
    fun `ArrayFramePixels and GamePixels read sub-rectangles and compute colour metrics`() {
        val frame = frameOf(10, 8) { x, y -> GamePixels.rgb(x * 10, y * 20, 50) }
        assertEquals(GamePixels.rgb(30, 40, 50), frame.getPixel(3, 2))

        val buf = IntArray(12)
        frame.getPixels(buf, offset = 1, stride = 5, x = 2, y = 3, width = 3, height = 2)
        assertEquals(GamePixels.rgb(20, 60, 50), buf[1])
        assertEquals(GamePixels.rgb(40, 60, 50), buf[3])
        assertEquals(GamePixels.rgb(20, 80, 50), buf[6])
        assertEquals(GamePixels.rgb(40, 80, 50), buf[8])

        val pureRed = GamePixels.rgb(255, 0, 0)
        val pureBlue = GamePixels.rgb(0, 0, 255)
        assertTrue(abs(GamePixels.luma(pureRed) - 255 * 0.299) < 0.01)
        assertEquals(GamePixels.rgb(127, 0, 127), GamePixels.blend(pureRed, pureBlue, 0.5f))
        assertEquals(GamePixels.rgb(127, 0, 127), GamePixels.mean(listOf(pureRed, pureBlue)))
        assertTrue(abs(GamePixels.colourDistance(GamePixels.rgb(10, 20, 30), GamePixels.rgb(13, 24, 30)) - 5.0) < 1e-6)
    }

    @Test
    fun `stillPieceByPiece catches typing at the end of a wide box whose whole mean stays still`() {
        val width = 400
        val height = 80
        val lineBox = box(0, 20, 400, 60)
        val before = GameFrameLook.smallOf(frameOf(width, height) { _, _ -> grey(40) })
        val typingEnd = GameFrameLook.smallOf(
            frameOf(width, height) { x, y ->
                if (x in 376 until 400 && y in 28 until 52) grey(70) else grey(40)
            },
        )

        assertFalse(GameFrameLook.stillBetween(null, typingEnd, lineBox))
        assertTrue(GameFrameLook.stillBetween(before, typingEnd, lineBox))
        assertFalse(GameFrameLook.stillPieceByPiece(before, typingEnd, lineBox, piece = 40))
    }

    @Test
    fun `uniform shading is not replaced while changed glyphs are`() {
        val width = 240
        val height = 80
        val target = box(20, 20, 212, 52)

        assertNull(GameFrameLook.lookOf(frameOf(width, height) { _, _ -> grey(200) }, box(0, 0, 20, 10)))

        val original = frameOf(width, height) { x, _ ->
            if (x in 30 until 120 && (x / 4) % 2 == 0) grey(30) else grey(220)
        }
        val shaded = frameOf(width, height) { x, _ ->
            if (x in 30 until 120 && (x / 4) % 2 == 0) grey(0) else grey(180)
        }
        val differentWords = frameOf(width, height) { x, _ ->
            if (x in 120 until 205 && (x / 4) % 2 == 1) grey(30) else grey(220)
        }

        val lookOriginal = GameFrameLook.lookOf(original, target)!!
        val lookShaded = GameFrameLook.lookOf(shaded, target)!!
        val lookDifferent = GameFrameLook.lookOf(differentWords, target)!!

        assertFalse(GameFrameLook.replaced(lookOriginal, lookShaded))
        assertTrue(GameFrameLook.replaced(lookOriginal, lookDifferent))

        val published = mapOf("k" to (target to lookOriginal))
        assertFalse(GameFrameLook.replacedSince(shaded, published, target))
        assertTrue(GameFrameLook.replacedSince(differentWords, published, target))

        val oneCellChanged = lookOriginal.copyOf().also { it[0] += 20 }
        assertTrue(GameFrameLook.largestDifference(lookOriginal, oneCellChanged) > GameFrameLook.STILL_LOOK_LUMA)
    }

    @Test
    fun `shiftOf and sharedShift recover non-multiple vertical scroll offsets`() {
        val width = 200
        val height = 300
        val pattern1: (Int, Int) -> Int = { x, relY ->
            if (relY !in 0 until 32) grey(230)
            else if (x < 90) grey((15 + relY * 4).coerceIn(0, 255)) else grey(220)
        }
        val pattern2: (Int, Int) -> Int = { x, relY ->
            if (relY !in 0 until 32) grey(230)
            else grey((20 + relY * 5 + (x % 24) * 3).coerceIn(0, 255))
        }
        val box1 = box(20, 100, 180, 132)
        val box2 = box(20, 160, 180, 192)
        val before = frameOf(width, height) { x, y ->
            when {
                y in 100 until 132 -> pattern1(x, y - 100)
                y in 160 until 192 -> pattern2(x, y - 160)
                else -> grey(230)
            }
        }
        val dy = -13
        val scrolled = frameOf(width, height) { x, y ->
            when {
                // First line scrolled off / replaced by plain panel; second line scrolled by dy = -13.
                y in (160 + dy) until (192 + dy) -> pattern2(x, y - (160 + dy))
                else -> grey(230)
            }
        }

        val look1 = GameFrameLook.lookOf(before, box1)!!
        val look2 = GameFrameLook.lookOf(before, box2)!!
        assertNull(GameFrameLook.shiftOf(scrolled, box1, look1))
        assertEquals(dy, GameFrameLook.shiftOf(scrolled, box2, look2))

        val published = mapOf("top" to (box1 to look1), "second" to (box2 to look2))
        assertEquals(dy, GameFrameLook.sharedShift(scrolled, published, listOf("top", "second")))
        assertFalse(GameFrameLook.foundAt(scrolled, published, "top", dy))
        assertTrue(GameFrameLook.foundAt(scrolled, published, "second", dy))
    }

    @Test
    fun `frontLayer drops faint overlapping background text and keeps strong foreground ink`() {
        val width = 240
        val height = 120
        val frontBox = box(20, 20, 100, 60)
        val faintBehindBox = box(68, 20, 228, 60)
        val separateBox = box(20, 80, 180, 110)

        val frame = frameOf(width, height) { x, y ->
            when {
                y in 20 until 60 && x in 20 until 84 && (x / 4) % 5 < 2 -> grey(245)
                y in 20 until 60 && x in 100 until 220 && (x / 4) % 10 == 0 -> grey(80)
                y in 84 until 104 && x in 30 until 120 && (x / 4) % 3 == 0 -> grey(240)
                else -> grey(30)
            }
        }

        assertFalse(GameFrameLook.inked(frameOf(width, height) { _, _ -> grey(40) }, frontBox))
        assertTrue(GameFrameLook.inked(frame, frontBox))

        val front = GameLine("前景の台詞", frontBox, backdropError = 2.0)
        val behind = GameLine("背景の残像", faintBehindBox, backdropError = 2.0)
        val other = GameLine("別の行", separateBox, backdropError = 2.0)

        assertEquals(listOf(front, other), GameFrameLook.frontLayer(frame, listOf(front, behind, other)))
    }

    @Test
    fun `dimmed contrastOf and sceneChanged distinguish dimmed pop-up backgrounds and scene cuts`() {
        val width = 120
        val height = 60
        val target = box(10, 10, 110, 50)

        val underDimmer = frameOf(width, height) { x, _ -> if (x % 4 == 0) grey(80) else grey(25) }
        val activePanel = frameOf(width, height) { x, _ -> if (x % 4 == 0) grey(245) else grey(30) }

        assertTrue(GameFrameLook.dimmed(underDimmer, target))
        assertTrue(GameFrameLook.contrastOf(underDimmer, target) < GameFrameLook.ACTIVE_CONTRAST)

        assertFalse(GameFrameLook.dimmed(activePanel, target))
        assertTrue(GameFrameLook.contrastOf(activePanel, target) >= GameFrameLook.ACTIVE_CONTRAST)

        val gridA = GameFrameLook.lumaGridOf(IntArray(GameFrameLook.GRID_COLUMNS * GameFrameLook.GRID_ROWS) { grey(40) })
        val slightMove = gridA.copyOf().also { for (i in 0 until 20) it[i] = 90 }
        val cut = IntArray(gridA.size) { 120 }

        assertFalse(GameFrameLook.sceneChanged(null, gridA))
        assertFalse(GameFrameLook.sceneChanged(gridA, slightMove))
        assertTrue(GameFrameLook.sceneChanged(gridA, cut))
    }
}
