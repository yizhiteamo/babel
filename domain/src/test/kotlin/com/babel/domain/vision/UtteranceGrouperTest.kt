package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which balloons get joined, and when they are released.
 *
 * The holding is the part that costs something: a balloon kept back is a
 * balloon not yet on screen, and incremental publishing was measured and built
 * deliberately (`docs/milestones/v2.md`). So the cases that must **not** hold
 * are guarded as carefully as the ones that must.
 */
class UtteranceGrouperTest {

    private fun balloon(text: String) = region(text, enclosed = true)

    private fun onArt(text: String) = region(text, enclosed = false)

    private fun region(text: String, enclosed: Boolean): TextRegion {
        val bounds = TextBounds(0, 0, 40, 100, CoordinateSpace.SCREEN)
        return TextRegion(
            lines = listOf(RecognizedLine(text = text, bounds = bounds)),
            bounds = bounds,
            orientation = TextOrientation.VERTICAL,
            enclosure = if (enclosed) TextBounds(0, 0, 60, 120, CoordinateSpace.SCREEN) else null,
        )
    }

    @Test
    fun `the measured split is held and released as one`() {
        val grouper = UtteranceGrouper()

        // `わたしの` cannot stand on its own, so nothing is published yet.
        assertNull(grouper.offer(balloon("わたしの")))

        val utterance = grouper.offer(balloon("めを見て"))
        assertEquals("わたしのめを見て", utterance?.text)
        assertEquals(2, utterance?.regions?.size)
        assertTrue(utterance!!.isShared)
    }

    @Test
    fun `a complete balloon goes out on its own immediately`() {
        val grouper = UtteranceGrouper()

        val first = grouper.offer(balloon("データは集まった"))
        assertEquals("データは集まった", first?.text)
        assertEquals(1, first?.regions?.size)
        assertTrue(!first!!.isShared)

        // And the one after it is unaffected by the one before.
        assertEquals("いいの?", grouper.offer(balloon("いいの?"))?.text)
    }

    @Test
    fun `the measured pairs that must not be joined are not`() {
        // Joining either of these loses one of the two lines — measured against
        // the real service.
        // The ellipses come back repaired — a run collapses to one `…` — which
        // is `OcrPunctuation` doing its job on the way through.
        val grouper = UtteranceGrouper()
        assertEquals("はい", grouper.offer(balloon("はい"))?.text)
        assertEquals("…あ", grouper.offer(balloon("……あ"))?.text)
        assertEquals("…先生?", grouper.offer(balloon("……先生?"))?.text)
    }

    @Test
    fun `lettering on the artwork is never held`() {
        val grouper = UtteranceGrouper()

        // Ends in から and would be held if it were a balloon. Page 05's line
        // does continue — two regions later, not in the next one — so holding
        // it would join it to the wrong neighbour.
        val utterance = grouper.offer(onArt("私はたった今から"))
        assertEquals("私はたった今から", utterance?.text)
        assertEquals(1, utterance?.regions?.size)
    }

    @Test
    fun `a chain stops at the cap`() {
        val grouper = UtteranceGrouper(maxGroup = 3)

        assertNull(grouper.offer(balloon("わたしの")))
        assertNull(grouper.offer(balloon("あなたの")))
        // Still unfinished, but the cap releases it rather than letting one
        // misread swallow the rest of the page.
        val utterance = grouper.offer(balloon("かれの"))
        assertEquals(3, utterance?.regions?.size)
        assertEquals("わたしのあなたのかれの", utterance?.text)
    }

    @Test
    fun `a page ending mid-sentence releases what it holds`() {
        val grouper = UtteranceGrouper()

        assertNull(grouper.offer(balloon("わたしの")))
        val remaining = grouper.flush()
        assertEquals("わたしの", remaining?.text)
        assertEquals(1, remaining?.regions?.size)
        // And the grouper is empty afterwards.
        assertNull(grouper.flush())
    }

    @Test
    fun `flush is empty when nothing is held`() {
        val grouper = UtteranceGrouper()
        grouper.offer(balloon("データは集まった"))
        assertNull(grouper.flush())
    }

    @Test
    fun `the joined text is repaired before it is joined`() {
        // `......` is how manga-ocr writes `……`, and a provider given the raw
        // form returns the dots without the words. The repair has to happen
        // before the join so the seam is not left raw.
        val grouper = UtteranceGrouper()
        assertNull(grouper.offer(balloon("そっちは私の...")))
        val utterance = grouper.offer(balloon("使用済み..."))
        assertEquals("そっちは私の…使用済み…", utterance?.text)
    }
}
