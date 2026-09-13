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
        val regions = grouper.group(measuredSamplePage())

        assertEquals(4, regions.size, "expected four bubbles, got ${regions.map { it.text }}")
    }

    /**
     * The correction that matters most. ML Kit returned these two columns as a
     * single block reading `いい天気今日は`; `今日は` sits further right and so
     * comes first in Japanese.
     */
    @Test
    fun `columns within a bubble are ordered right to left`() {
        val regions = grouper.group(measuredSamplePage())

        val weather = regions.single { it.text.contains("天気") }
        assertEquals("今日はいい天気", weather.text)
    }

    /** This bubble arrived as two separate blocks and has to be rejoined. */
    @Test
    fun `columns split across blocks are joined into one region`() {
        val regions = grouper.group(measuredSamplePage())

        val greeting = regions.single { it.text.contains("おはよう") }
        assertEquals("おはよう1ございます", greeting.text)
        assertEquals(2, greeting.lines.size)
    }

    @Test
    fun `a region covers all of its lines`() {
        val regions = grouper.group(measuredSamplePage())

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
        val regions = grouper.group(measuredSamplePage())

        val topRow = regions.filter { it.bounds.top < 400 }
        assertEquals(2, topRow.size)
        assertTrue(topRow.none { it.text.contains("おはよう") && it.text.contains("天気") })
    }

    /** Vertically separated columns must not join even when horizontally close. */
    @Test
    fun `columns in stacked panels do not merge`() {
        val regions = grouper.group(measuredSamplePage())

        val greeting = regions.single { it.text.contains("おはよう") }
        assertTrue(
            greeting.lines.none { it.text.contains("何時") },
            "a bubble from the lower panel leaked into the upper one",
        )
    }

    @Test
    fun `regions are returned top row first, right to left`() {
        val regions = grouper.group(measuredSamplePage())

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

        val regions = grouper.group(lines)

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
        )

        assertEquals(1, regions.size)
        assertEquals("real", regions.single().text)
    }

    @Test
    fun `no input yields no regions`() {
        assertTrue(grouper.group(emptyList()).isEmpty())
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

        val regions = grouper.group(lines)

        assertEquals(1, regions.size)
        assertEquals("CBA", regions.single().text)
    }

    // --- orientation ---

    @Test
    fun `the measured sample is detected as vertical without being told`() {
        val regions = grouper.group(measuredSamplePage())

        assertTrue(regions.all { it.orientation == TextOrientation.VERTICAL })
    }

    /**
     * A caption beside dialogue must not be absorbed into it. Adjacency means
     * different things in each orientation, so mixing them would corrupt the
     * bubbles themselves, not merely their order.
     */
    @Test
    fun `vertical dialogue and a horizontal caption do not merge`() {
        val lines = listOf(
            // Two columns of dialogue.
            line("ですか", 300, 100, 340, 300),
            line("お元気", 360, 100, 400, 300),
            // A caption running horizontally, immediately below them.
            line("a narrow caption", 300, 320, 700, 356),
        )

        val regions = grouper.group(lines)

        assertEquals(2, regions.size)
        val dialogue = regions.single { it.orientation == TextOrientation.VERTICAL }
        val caption = regions.single { it.orientation == TextOrientation.HORIZONTAL }
        assertEquals("お元気ですか", dialogue.text)
        assertEquals("a narrow caption", caption.text)
    }

    /**
     * A single character is square, so shape cannot classify it. It should
     * follow the page rather than defaulting arbitrarily.
     */
    @Test
    fun `an ambiguous square line follows the page majority`() {
        val lines = measuredSamplePage() + line("！", 500, 200, 536, 236)

        val regions = grouper.group(lines)

        val exclamation = regions.single { it.text == "！" }
        assertEquals(TextOrientation.VERTICAL, exclamation.orientation)
    }

    /**
     * When the engine reports an orientation it wins over shape. ML Kit does
     * report one, and is better placed to know — particularly for the square
     * boxes where shape says nothing at all.
     */
    @Test
    fun `an engine-supplied orientation overrides the shape heuristic`() {
        val wideButVertical = RecognizedLine(
            text = "engine says vertical",
            bounds = TextBounds(100, 100, 500, 140, CoordinateSpace.SCREEN),
            orientation = TextOrientation.VERTICAL,
        )

        val regions = grouper.group(listOf(wideButVertical))

        assertEquals(TextOrientation.VERTICAL, regions.single().orientation)
    }

    @Test
    fun `a forced orientation overrides everything`() {
        val regions = grouper.group(measuredSamplePage(), forcedOrientation = TextOrientation.HORIZONTAL)

        assertTrue(regions.all { it.orientation == TextOrientation.HORIZONTAL })
    }
}
