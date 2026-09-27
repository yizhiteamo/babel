package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals

class MangaReadingOrderTest {

    private fun box(left: Int, top: Int, width: Int = 100, height: Int = 60) =
        TextBounds(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            space = CoordinateSpace.SCREEN,
        )

    private fun order(vararg boxes: TextBounds, rightToLeft: Boolean) =
        boxes.sortedWith(MangaReadingOrder.of(pageHeight = PAGE, rightToLeft = rightToLeft))

    @Test
    fun `japanese reads the right of a row first`() {
        val left = box(left = 100, top = 200)
        val right = box(left = 800, top = 200)

        assertEquals(listOf(right, left), order(left, right, rightToLeft = true))
    }

    @Test
    fun `korean reads the left of a row first`() {
        val left = box(left = 100, top = 200)
        val right = box(left = 800, top = 200)

        assertEquals(listOf(left, right), order(left, right, rightToLeft = false))
    }

    @Test
    fun `a row is banded, so a slightly lower balloon is still in it`() {
        // Within ROW_FRACTION of the page height, so the same row either way —
        // and the whole point of banding: a two-pixel difference between two
        // box tops must not reorder a row.
        val higher = box(left = 100, top = 200)
        val lower = box(left = 800, top = 200 + PAGE / 100)

        assertEquals(listOf(lower, higher), order(higher, lower, rightToLeft = true))
        assertEquals(listOf(higher, lower), order(higher, lower, rightToLeft = false))
    }

    @Test
    fun `a balloon a row further down comes second whichever way the row runs`() {
        val top = box(left = 100, top = 100)
        val bottom = box(left = 800, top = 900)

        assertEquals(listOf(top, bottom), order(top, bottom, rightToLeft = true))
        assertEquals(listOf(top, bottom), order(top, bottom, rightToLeft = false))
    }

    private companion object {
        const val PAGE = 1920
    }
}
