package com.babel.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RowGradientTest {

    private val panel = RowGradient(
        left = listOf(0xFF000000.toInt(), 0xFF102030.toInt()),
        right = listOf(0xFF0000FF.toInt(), 0xFF102030.toInt()),
    )

    @Test
    fun `the ends are the sampled colours`() {
        assertEquals(0xFF000000.toInt(), panel.colorAt(0, 0f))
        assertEquals(0xFF0000FF.toInt(), panel.colorAt(0, 1f))
    }

    @Test
    fun `between the ends the colour is interpolated`() {
        assertEquals(0xFF000080.toInt(), panel.colorAt(0, 0.5f))
    }

    @Test
    fun `a plain row stays plain`() {
        assertEquals(0xFF102030.toInt(), panel.colorAt(1, 0.3f))
    }

    @Test
    fun `rows and fractions outside the gradient are clamped`() {
        assertEquals(panel.colorAt(1, 1f), panel.colorAt(5, 2f))
    }

    @Test
    fun `two equal gradients are equal`() {
        assertEquals(panel, panel.copy(left = panel.left.toList()))
    }

    @Test
    fun `each row needs a colour at both ends`() {
        assertFailsWith<IllegalArgumentException> { RowGradient(listOf(1), emptyList()) }
    }
}
