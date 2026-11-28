package com.babel.domain.game

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.SourceStyle
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GameStyleMemoryTest {

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
    fun `emOf estimates em from full-width and half-width units and rejects short wrapped or out-of-ratio boxes`() {
        val memory = GameStyleMemory()

        // 4 full-width kana = 4.0 units; typeHeight = 36 -> fromHeight = 36 / 0.88 = 40.909, width = 164 -> em = 41.0.
        val valid = GameLine("こんにちは".take(4), box(20, 20, 184, 58), backdropError = 2.0, typeHeight = 36)
        assertEquals(41.0, memory.emOf(valid)!!, 0.001)

        // Half-width ASCII counts HALF_WIDTH (0.55), ASCII space counts 0.0, full-width space '　' counts 1.0.
        // "AB　…" -> 0.55 + 0.55 + 1.0 + 1.0 = 3.1 units; width = 124 -> em = 40.0.
        val mixed = GameLine("AB 　…", box(20, 20, 144, 58), backdropError = 2.0, typeHeight = 36)
        assertEquals(40.0, memory.emOf(mixed)!!, 0.001)

        // Too short (< MIN_EM_UNITS = 2.0).
        val singleChar = GameLine("あ", box(20, 20, 60, 58), backdropError = 2.0, typeHeight = 36)
        assertNull(memory.emOf(singleChar))

        // Wrapped multi-line utterance (bounds.height > typeHeight * MAX_ONE_LINE_OF_TYPE = 57.6).
        val wrapped = GameLine("こんにちは", box(20, 20, 184, 85), backdropError = 2.0, typeHeight = 36)
        assertNull(memory.emOf(wrapped))

        // Width far off typeHeight ratio (dropped marks or misread).
        val mismatched = GameLine("こんにちは", box(20, 20, 80, 58), backdropError = 2.0, typeHeight = 36)
        assertNull(memory.emOf(mismatched))
    }

    @Test
    fun `steadySize groups nearby heights blocks upward creep separates speakers and uses median em`() {
        val memory = GameStyleMemory()

        // Without em samples, heights within SAME_TYPE_RATIO (0.86) share the tallest measured size.
        assertEquals(37, memory.steadySize(speaker = false, measured = 37, em = null))
        assertEquals(41, memory.steadySize(speaker = false, measured = 41, em = null))
        assertEquals(41, memory.steadySize(speaker = false, measured = 38, em = null))

        // 44 is within 0.86 of 41 (41/44 = 0.93), but NOT within 0.86 of smallest 37 (37/44 = 0.8409 < 0.86),
        // so it opens a separate TypeSize bucket instead of creeping the 37..41 bucket upward.
        assertEquals(44, memory.steadySize(speaker = false, measured = 44, em = null))
        assertEquals(41, memory.steadySize(speaker = false, measured = 39, em = null))

        // Speaker names keep their own independent TypeSize buckets.
        assertEquals(38, memory.steadySize(speaker = true, measured = 38, em = null))

        // Once em samples are recorded, steadySize uses (medianEm * GLYPH_OF_EM).toInt() instead of tallest.
        assertEquals((40.0 * GameStyleMemory.GLYPH_OF_EM).toInt(), memory.steadySize(speaker = false, measured = 40, em = 40.0))
        memory.steadySize(speaker = false, measured = 40, em = 42.0)
        memory.steadySize(speaker = false, measured = 40, em = 46.0)
        // Sorted ems [40.0, 42.0, 46.0] -> median 42.0 -> (42.0 * 0.88).toInt() = 36.
        assertEquals(36, memory.steadySize(speaker = false, measured = 41, em = null))

        // Rolling window caps ems at MAX_EM_SAMPLES (32), evicting earlier samples.
        repeat(GameStyleMemory.MAX_EM_SAMPLES) {
            memory.steadySize(speaker = false, measured = 40, em = 50.0)
        }
        assertEquals((50.0 * GameStyleMemory.GLYPH_OF_EM).toInt(), memory.steadySize(speaker = false, measured = 40, em = null))
    }

    @Test
    fun `steadyWeight freezes per-kind median after WEIGHT_SAMPLES measurements`() {
        val memory = GameStyleMemory()
        assertNull(memory.weightOf(label = false))
        assertNull(memory.weightOf(label = true))

        // Record 12 dialogue weights: six 0.14f and six 0.16f -> sorted[6] = 0.16f.
        repeat(6) {
            memory.steadyWeight(label = false, measured = 0.14f)
            memory.steadyWeight(label = false, measured = 0.16f)
        }
        assertEquals(0.16f, memory.weightOf(label = false))

        // Beyond WEIGHT_SAMPLES (12), heavier lines do not move the settled dialogue weight.
        repeat(10) {
            assertEquals(0.16f, memory.steadyWeight(label = false, measured = 0.30f))
        }
        assertEquals(0.16f, memory.weightOf(label = false))

        // Label/speaker weights remain independent.
        assertEquals(0.22f, memory.steadyWeight(label = true, measured = 0.22f))
        assertEquals(0.22f, memory.weightOf(label = true))
    }

    @Test
    fun `withRowStyle unifies stroke weight and close ink across a label row and evicts beyond MAX_ROW_STYLES`() {
        val memory = GameStyleMemory()
        val baseInk = GamePixels.rgb(0x46, 0x63, 0x98)
        val closeInk = GamePixels.rgb(0x49, 0x6F, 0x8F) // distance ~15.4 <= ROW_INK_DISTANCE (45.0)
        val farInk = GamePixels.rgb(0xE0, 0x50, 0x40)

        val firstRead = GameLine("ノーマルスキル", box(40, 100, 160, 136), backdropError = 2.0, typeHeight = 30)
        val firstStyle = SourceStyle(foregroundColor = baseInk, strokeWeight = 0.15f)
        val recorded = memory.withRowStyle(
            read0 = firstRead,
            seen = 100 to 136,
            conversational = false,
            speaker = false,
            inked = firstRead,
            look0 = firstStyle,
        )
        assertEquals(0.15f, recorded.strokeWeight)
        assertEquals(baseInk, recorded.foregroundColor)

        // Later frame where a sibling label settles alone (seen == null): inherits row weight and close ink.
        val soloMate = GameLine("EXスキル", box(200, 102, 300, 138), backdropError = 2.0, typeHeight = 30)
        val unified = memory.withRowStyle(
            read0 = soloMate,
            seen = null,
            conversational = false,
            speaker = false,
            inked = soloMate,
            look0 = SourceStyle(foregroundColor = closeInk, strokeWeight = 0.19f),
        )
        assertEquals(0.15f, unified.strokeWeight)
        assertEquals(baseInk, unified.foregroundColor)

        // Far ink (> ROW_INK_DISTANCE) keeps its own foregroundColor while still sharing strokeWeight.
        val distinctColourMate = memory.withRowStyle(
            read0 = soloMate,
            seen = null,
            conversational = false,
            speaker = false,
            inked = soloMate,
            look0 = SourceStyle(foregroundColor = farInk, strokeWeight = 0.19f),
        )
        assertEquals(0.15f, distinctColourMate.strokeWeight)
        assertEquals(farInk, distinctColourMate.foregroundColor)

        // Conversational dialogue on the same y-band with seen == null does not take label rowStyles.
        val dialogueOnRow = GameLine("先生、おはようございます。", box(40, 100, 340, 136), backdropError = 2.0, typeHeight = 30)
        val untouched = memory.withRowStyle(
            read0 = dialogueOnRow,
            seen = null,
            conversational = true,
            speaker = false,
            inked = dialogueOnRow,
            look0 = SourceStyle(foregroundColor = closeInk, strokeWeight = 0.21f),
        )
        assertEquals(0.21f, untouched.strokeWeight)
        assertEquals(closeInk, untouched.foregroundColor)

        // Recording MAX_ROW_STYLES (16) newer rows at distant y positions evicts the y = 100..136 row.
        for (i in 1..GameStyleMemory.MAX_ROW_STYLES) {
            val top = 300 + i * 80
            val r = GameLine("スキル", box(40, top, 140, top + 30), backdropError = 2.0, typeHeight = 26)
            memory.withRowStyle(r, top to (top + 30), false, false, r, SourceStyle(foregroundColor = baseInk, strokeWeight = 0.12f))
        }
        val afterEvict = memory.withRowStyle(
            read0 = soloMate,
            seen = null,
            conversational = false,
            speaker = false,
            inked = soloMate,
            look0 = SourceStyle(foregroundColor = closeInk, strokeWeight = 0.19f),
        )
        assertEquals(0.19f, afterEvict.strokeWeight)
        assertEquals(closeInk, afterEvict.foregroundColor)
    }

    @Test
    fun `lookOf respects iconBoxes left limit and falls back to remembered weight when strokes are unmeasured`() {
        val memory = GameStyleMemory()
        val panel = grey(30)
        val ink = grey(240)
        val frame = frameOf(320, 120) { x, y ->
            when {
                // Extra ink left of box (x in 52..59) that would be absorbed without iconBoxes limit.
                y in 46..68 && x in 52..59 && (x % 4) < 2 -> ink
                // Main glyph strokes inside box (60..160, 40..76): 6-px wide runs at 70..75 and 90..95.
                y in 46..68 && (x in 70..75 || x in 90..95) -> ink
                else -> panel
            }
        }
        val recognised = GameLine("ユズ", box(60, 40, 160, 76), backdropError = 1.0, typeHeight = 30)
        val badgeBox = box(36, 40, 56, 76)
        val (grown, style) = memory.lookOf(frame, recognised, others = listOf(recognised), iconBoxes = listOf(badgeBox), speaker = true)

        // Left limit from badgeBox ((56 + 60) / 2 = 58) prevents inkExtent from growing left over 52..59.
        assertEquals(60, grown.bounds.left)
        assertNotNull(style.backdrop)
        assertEquals(30, style.glyphSizePx)
        assertEquals(0.2f, style.strokeWeight!!, 0.001f)

        // Unmeasured narrow box (width <= 2 * OUTLINE_PROBE_PX = 4) falls back to remembered speaker weight (0.2f).
        val tinyLine = GameLine("！", box(200, 40, 204, 76), backdropError = 1.0, typeHeight = 30)
        val (_, fallbackStyle) = memory.lookOf(frame, tinyLine, others = listOf(tinyLine), iconBoxes = emptyList(), speaker = true)
        assertEquals(0.2f, fallbackStyle.strokeWeight!!, 0.001f)
    }

    @Test
    fun `clear resets sizes weights and row styles to initial state`() {
        val memory = GameStyleMemory()

        // Populate all four stateful collections: typeSizes, labelWeights, lineWeights, rowStyles.
        memory.steadySize(speaker = false, measured = 44, em = 48.0)
        memory.steadySize(speaker = true, measured = 38, em = 40.0)
        repeat(GameStyleMemory.WEIGHT_SAMPLES) {
            memory.steadyWeight(label = false, measured = 0.14f)
            memory.steadyWeight(label = true, measured = 0.22f)
        }
        val rowLabel = GameLine("パッシブスキル", box(40, 100, 180, 136), backdropError = 2.0, typeHeight = 30)
        memory.withRowStyle(
            read0 = rowLabel,
            seen = 100 to 136,
            conversational = false,
            speaker = false,
            inked = rowLabel,
            look0 = SourceStyle(foregroundColor = GamePixels.rgb(70, 99, 152), strokeWeight = 0.15f),
        )

        memory.clear()

        assertNull(memory.weightOf(label = false))
        assertNull(memory.weightOf(label = true))
        assertEquals(0.28f, memory.steadyWeight(label = false, measured = 0.28f))
        assertEquals(0.19f, memory.steadyWeight(label = true, measured = 0.19f))
        assertEquals(32, memory.steadySize(speaker = false, measured = 32, em = null))
        assertEquals(30, memory.steadySize(speaker = true, measured = 30, em = null))

        val afterClearRow = memory.withRowStyle(
            read0 = rowLabel,
            seen = null,
            conversational = false,
            speaker = false,
            inked = rowLabel,
            look0 = SourceStyle(foregroundColor = GamePixels.rgb(73, 111, 143), strokeWeight = 0.21f),
        )
        assertEquals(0.21f, afterClearRow.strokeWeight)
        assertEquals(GamePixels.rgb(73, 111, 143), afterClearRow.foregroundColor)
    }
}
