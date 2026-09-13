package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Fixtures are the boxes ML Kit actually returned for
 * `docs/testing/manga-sample.png`, copied from the probe's output rather than
 * invented. Real coordinates carry the awkward cases — columns of unequal
 * length, boxes that nearly touch — that made-up numbers would miss.
 */
class TextRegionGrouperTest {

    private val grouper = TextRegionGrouper()

    private fun line(text: String, left: Int, top: Int, right: Int, bottom: Int) =
        RecognizedLine(text, TextBounds(left, top, right, bottom, CoordinateSpace.SCREEN))

    /** Every line the recogniser produced for the four-bubble sample page. */
    private fun measuredSamplePage() = listOf(
        line("1ございます", 204, 101, 242, 302),
        line("何時ですか", 211, 521, 252, 722),
        line("おはよう", 256, 102, 298, 261),
        line("いい天気", 1157, 127, 1193, 282),
        line("今日は", 1215, 122, 1251, 238),
        line("侍ってます", 1158, 522, 1194, 721),
        line("統前で", 1213, 522, 1245, 637),
    )

    @Test
    fun `the sample page resolves to one region per bubble`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        assertEquals(4, regions.size, "expected four bubbles, got ${regions.map { it.text }}")
    }

    /**
     * The correction that matters most. ML Kit returned these two columns as a
     * single block reading `いい天気今日は`; `今日は` sits further right and so
     * comes first in Japanese.
     */
    @Test
    fun `columns within a bubble are ordered right to left`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        val weather = regions.single { it.text.contains("天気") }
        assertEquals("今日はいい天気", weather.text)
    }

    /** This bubble arrived as two separate blocks and has to be rejoined. */
    @Test
    fun `columns split across blocks are joined into one region`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        val greeting = regions.single { it.text.contains("おはよう") }
        assertEquals("おはよう1ございます", greeting.text)
        assertEquals(2, greeting.lines.size)
    }

    @Test
    fun `a region covers all of its lines`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        val greeting = regions.single { it.text.contains("おはよう") }
        // Union of (204,101,242,302) and (256,102,298,261).
        assertEquals(204, greeting.bounds.left)
        assertEquals(101, greeting.bounds.top)
        assertEquals(298, greeting.bounds.right)
        assertEquals(302, greeting.bounds.bottom)
    }

    /**
     * Bubbles in different panels must not merge. These two sit at the same
     * height on opposite sides of the page.
     */
    @Test
    fun `distant bubbles stay separate`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        val topRow = regions.filter { it.bounds.top < 400 }
        assertEquals(2, topRow.size)
        assertTrue(topRow.none { it.text.contains("おはよう") && it.text.contains("天気") })
    }

    /** Vertically separated columns must not join even when horizontally close. */
    @Test
    fun `columns in stacked panels do not merge`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        val greeting = regions.single { it.text.contains("おはよう") }
        assertTrue(
            greeting.lines.none { it.text.contains("何時") },
            "a bubble from the lower panel leaked into the upper one",
        )
    }

    @Test
    fun `regions are returned top row first, right to left`() {
        val regions = grouper.group(measuredSamplePage(), ReadingDirection.VERTICAL_RTL)

        // Japanese pages read right to left, so the top-right bubble leads.
        assertTrue(regions[0].text.contains("天気"), "expected top-right first, got ${regions[0].text}")
        assertTrue(regions[1].text.contains("おはよう"))
    }

    @Test
    fun `horizontal text groups by stacking and reads top down`() {
        val lines = listOf(
            line("second line", 100, 160, 400, 200),
            line("first line", 100, 100, 400, 140),
        )

        val regions = grouper.group(lines, ReadingDirection.HORIZONTAL_LTR)

        assertEquals(1, regions.size)
        assertEquals("first linesecond line", regions.single().text)
    }

    @Test
    fun `blank and empty lines are ignored`() {
        val regions = grouper.group(
            listOf(
                line("   ", 204, 101, 242, 302),
                line("empty box", 50, 50, 50, 50),
                line("real", 256, 102, 298, 261),
            ),
            ReadingDirection.VERTICAL_RTL,
        )

        assertEquals(1, regions.size)
        assertEquals("real", regions.single().text)
    }

    @Test
    fun `no input yields no regions`() {
        assertTrue(grouper.group(emptyList(), ReadingDirection.VERTICAL_RTL).isEmpty())
    }

    /**
     * A chain of columns must end up in one bubble even when only neighbouring
     * pairs are close enough — the reason grouping is transitive.
     */
    @Test
    fun `a chain of adjacent columns forms a single region`() {
        val lines = listOf(
            line("A", 300, 100, 340, 300),
            line("B", 360, 100, 400, 300),
            line("C", 420, 100, 460, 300),
        )

        val regions = grouper.group(lines, ReadingDirection.VERTICAL_RTL)

        assertEquals(1, regions.size)
        assertEquals("CBA", regions.single().text)
    }
}
