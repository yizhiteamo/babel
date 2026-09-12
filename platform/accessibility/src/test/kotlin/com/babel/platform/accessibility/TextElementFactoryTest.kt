package com.babel.platform.accessibility

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.Revision
import com.babel.core.model.TextBounds
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class TextElementFactoryTest {

    private fun bounds(top: Int) = TextBounds(0, top, 200, top + 48, CoordinateSpace.SCREEN)

    private fun create(
        vararg raw: RawText,
        windowId: Int = 1,
        revision: Long = 0,
    ) = TextElementFactory.create(
        rawTexts = raw.toList(),
        windowId = windowId,
        packageName = "com.example.reader",
        revision = Revision(revision),
    )

    /**
     * The invariant the whole scroll path rests on: same text at a new position
     * keeps its id, so the coordinator reuses the existing translation instead
     * of re-translating the screen on every scroll tick.
     */
    @Test
    fun `identity survives a change of position`() {
        val first = create(RawText("Hello", bounds(top = 0)), revision = 1)
        val afterScroll = create(RawText("Hello", bounds(top = 640)), revision = 2)

        assertEquals(first.single().id, afterScroll.single().id)
        assertNotEquals(first.single().bounds, afterScroll.single().bounds)
    }

    @Test
    fun `different text gets a different id`() {
        val a = create(RawText("Hello", bounds(0))).single()
        val b = create(RawText("Goodbye", bounds(0))).single()

        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `whitespace-only differences keep the same id`() {
        val a = create(RawText("The  quick\nbrown fox", bounds(0))).single()
        val b = create(RawText("The quick brown fox", bounds(0))).single()

        assertEquals(a.id, b.id)
    }

    @Test
    fun `repeated text on one screen gets distinct ids`() {
        val elements = create(
            RawText("OK", bounds(top = 0)),
            RawText("OK", bounds(top = 100)),
            RawText("OK", bounds(top = 200)),
        )

        assertEquals(3, elements.map { it.id }.toSet().size)
    }

    @Test
    fun `repeated text keeps stable ids across scans in the same order`() {
        val first = create(RawText("OK", bounds(0)), RawText("OK", bounds(100)))
        val second = create(RawText("OK", bounds(20)), RawText("OK", bounds(120)))

        assertEquals(first.map { it.id }, second.map { it.id })
    }

    @Test
    fun `the same text in a different window is a different element`() {
        val a = create(RawText("Hello", bounds(0)), windowId = 1).single()
        val b = create(RawText("Hello", bounds(0)), windowId = 2).single()

        assertNotEquals(a.id, b.id)
    }

    /** Ids reach diagnostics, so they must not carry readable screen text. */
    @Test
    fun `id does not contain the source text`() {
        val element = create(RawText("confidential memo", bounds(0))).single()

        assertTrue(
            !element.id.value.contains("confidential"),
            "id leaked screen text: ${element.id.value}",
        )
    }

    @Test
    fun `password nodes are marked protected`() {
        val element = create(RawText("hunter2", bounds(0), isPassword = true)).single()

        assertTrue(element.isProtected)
    }

    @Test
    fun `revision is carried through so scans can be ordered`() {
        val element = create(RawText("Hello", bounds(0)), revision = 7).single()

        assertEquals(Revision(7), element.revision)
    }
}
