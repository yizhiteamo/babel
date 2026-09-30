package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The boxes below are the ones Edge reported on a phone, 1440 wide, for a
 * chapter served as a column of images (`docs/milestones/v2.md`).
 */
class ContentAreaTest {

    private fun box(left: Int, top: Int, width: Int, height: Int) =
        TextBounds(left, top, left + width, top + height, CoordinateSpace.SCREEN)

    @Test
    fun `one container is the page, as before`() {
        val page = box(0, 300, 1083, 1383)

        val result = assertNotNull(ContentArea.of(listOf(page), labels = listOf(box(40, 100, 300, 60))))

        assertEquals(page, result.area)
        assertEquals(listOf(page), result.members)
    }

    /** The measured screen: an image's tail above, the main one, the next one's head below. */
    @Test
    fun `images partly on screen above and below join the page`() {
        val above = box(0, 138, 1440, 353)
        val main = box(0, 488, 1440, 2142)
        val below = box(0, 2626, 1440, 189)
        val statusIcon = box(95, 13, 122, 107)
        val toolbarText = box(556, 2826, 328, 182)

        val result = assertNotNull(
            ContentArea.of(listOf(main), labels = listOf(statusIcon, above, below, toolbarText)),
        )

        assertEquals(box(0, 138, 1440, 2815 - 138), result.area)
        assertEquals(setOf(main, above, below), result.members.toSet())
    }

    @Test
    fun `three full images in a column are one page`() {
        val images = listOf(box(0, 0, 1440, 900), box(0, 900, 1440, 1400), box(0, 2300, 1440, 900))

        val result = assertNotNull(ContentArea.of(images, labels = emptyList()))

        assertEquals(box(0, 0, 1440, 3200), result.area)
    }

    /** A narrower node beside or below the page is something else — a caption, a sidebar. */
    @Test
    fun `a node of another width does not join`() {
        val main = box(0, 300, 1440, 2000)
        val caption = box(200, 2300, 1000, 120)

        val result = assertNotNull(ContentArea.of(listOf(main), labels = listOf(caption)))

        assertEquals(main, result.area)
    }

    /** A toolbar between two full-width nodes breaks the column. */
    @Test
    fun `a gap stops the column`() {
        val main = box(0, 300, 1440, 2000)
        val beyondToolbar = box(0, 2500, 1440, 300)

        val result = assertNotNull(ContentArea.of(listOf(main), labels = listOf(beyondToolbar)))

        assertEquals(main, result.area)
    }

    /** A full-width paragraph inside the page adds nothing and is not a member. */
    @Test
    fun `a node inside the page is not part of the column`() {
        val main = box(0, 300, 1440, 2000)
        val inside = box(0, 900, 1440, 200)

        val result = assertNotNull(ContentArea.of(listOf(main), labels = listOf(inside)))

        assertEquals(listOf(main), result.members)
    }

    @Test
    fun `no container is no page`() {
        assertNull(ContentArea.of(emptyList(), labels = listOf(box(0, 0, 100, 40))))
    }
}
