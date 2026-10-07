package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals

class NestedDetectionsTest {

    private fun box(left: Int, top: Int, width: Int, height: Int) =
        TextBounds(left, top, left + width, top + height, CoordinateSpace.SCREEN)

    /** The measured case: a name line reported as well as the block that holds it. */
    @Test
    fun `a piece of a block is dropped and the block kept`() {
        val block = box(791, 1332, 308, 249)
        val name = box(804, 1323, 267, 81)

        assertEquals(listOf(block), NestedDetections.dropNested(listOf(block, name)) { it })
        assertEquals(listOf(block), NestedDetections.dropNested(listOf(name, block)) { it })
    }

    @Test
    fun `neighbours that only touch are both kept`() {
        val left = box(335, 1323, 344, 257)
        val right = box(791, 1332, 308, 249)

        assertEquals(2, NestedDetections.dropNested(listOf(left, right)) { it }.size)
    }

    /** Two columns of one balloon overlap by a strip; that is not nesting. */
    @Test
    fun `a thin overlap is not nesting`() {
        val a = box(300, 100, 100, 200)
        val b = box(186, 210, 130, 240)

        assertEquals(2, NestedDetections.dropNested(listOf(a, b)) { it }.size)
    }

    @Test
    fun `the same box twice leaves one`() {
        val a = box(10, 10, 100, 50)

        assertEquals(1, NestedDetections.dropNested(listOf(a, a.copy())) { it }.size)
    }
}
