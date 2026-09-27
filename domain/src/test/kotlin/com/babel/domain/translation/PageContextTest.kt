package com.babel.domain.translation

import com.babel.core.model.Revision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageContextTest {

    private val page = Revision(1)
    private val next = Revision(2)

    @Test
    fun `the first line of a page has nothing to go on`() {
        val context = PageContext()
        context.note(page, "이부키, 뭐해?")
        assertNull(context.forLine("이부키, 뭐해?"))
    }

    @Test
    fun `later lines see the ones before them`() {
        val context = PageContext()
        context.note(page, "이부키, 뭐해?")
        context.note(page, "선생님, 줄게!")

        val offered = context.forLine("선생님, 줄게!")

        assertTrue(offered!!.contains("이부키, 뭐해?"))
        assertFalse(offered.contains("선생님, 줄게!"), "offered a line its own context")
    }

    /** The name problem: whatever the page called it first, it keeps calling it. */
    @Test
    fun `an agreed rendering travels to the rest of the page`() {
        val context = PageContext()
        context.note(page, "이부키")
        context.agree("이부키", "伊吹")
        context.note(page, "이부키쨩")

        val offered = context.forLine("이부키쨩")

        assertTrue(offered!!.contains("이부키 => 伊吹"))
    }

    @Test
    fun `a new page starts over`() {
        val context = PageContext()
        context.note(page, "이부키")
        context.agree("이부키", "伊吹")

        context.note(next, "다른 말")

        assertNull(context.forLine("다른 말"))
    }

    @Test
    fun `the block is bounded`() {
        val context = PageContext()
        repeat(60) { context.note(page, "line number $it with some length to it") }
        val offered = context.forLine("something else")
        assertTrue(offered!!.length <= 1_300, "context grew to ${offered.length}")
    }

    @Test
    fun `blank input is ignored`() {
        val context = PageContext()
        context.note(page, "   ")
        context.agree("", "x")
        context.agree("y", "")
        assertNull(context.forLine("anything"))
    }

    /** Re-reading the same page must not make the context grow without end. */
    @Test
    fun `a repeated line is not offered twice`() {
        val context = PageContext()
        context.note(page, "같은 말")
        context.note(page, "다른 말")
        context.note(page, "같은 말")

        val offered = context.forLine("다른 말")

        assertEquals(1, offered!!.split("같은 말").size - 1)
    }
}
