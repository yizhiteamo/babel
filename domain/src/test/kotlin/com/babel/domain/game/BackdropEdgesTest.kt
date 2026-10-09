package com.babel.domain.game

import kotlin.test.Test
import kotlin.test.assertEquals

class BackdropEdgesTest {

    private val panel = 0xFF1A2230.toInt()
    private val white = 0xFFF0F0F0.toInt()

    @Test
    fun `a stray light sample at one end takes its neighbour's colour`() {
        val left = listOf(panel, panel, white, panel, panel)
        val right = List(5) { panel }

        val (l, r) = BackdropEdges.repaired(left, right)

        assertEquals(List(5) { panel }, l)
        assertEquals(right, r)
    }

    @Test
    fun `a rule across the whole width is kept`() {
        val left = listOf(panel, panel, white, panel, panel)
        val right = listOf(panel, panel, white, panel, panel)

        assertEquals(left to right, BackdropEdges.repaired(left, right))
    }

    @Test
    fun `a gentle gradient is left alone`() {
        val left = listOf(0xFF101010, 0xFF202020, 0xFF303030, 0xFF404040).map { it.toInt() }
        val right = left

        assertEquals(left to right, BackdropEdges.repaired(left, right))
    }
}
