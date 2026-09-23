package com.babel.domain.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dividing one translation among the balloons it was translated for.
 *
 * The case that drove it is page 07: `わたしの` / `めを見て` joined and
 * translated as 看着我的眼睛, to be shown in two balloons
 * (`docs/milestones/v2.md`).
 */
class SharedTranslationTest {

    @Test
    fun `the measured case divides across its two balloons`() {
        val parts = SharedTranslation.divide("看着我的眼睛", listOf(5550, 4257))
        assertEquals(2, parts.size)
        assertEquals("看着我的眼睛", parts.joinToString(""))
        assertTrue(parts.all { it.isNotEmpty() }, "got $parts")
    }

    @Test
    fun `a lone balloon keeps the whole line`() {
        assertEquals(listOf("闭上……眼睛……"), SharedTranslation.divide("闭上……眼睛……", listOf(900)))
    }

    @Test
    fun `nothing is lost or duplicated`() {
        val text = "周围哭泣、骚动、逃走时，店主的话和指引都要无视"
        for (weights in listOf(listOf(1, 1), listOf(9, 1), listOf(1, 9), listOf(3, 2, 5))) {
            val parts = SharedTranslation.divide(text, weights)
            assertEquals(weights.size, parts.size, "$weights")
            assertEquals(text, parts.joinToString(""), "$weights")
        }
    }

    @Test
    fun `a break the author wrote is preferred to one arithmetic chose`() {
        // Proportionally the cut lands mid-clause; the comma is inside the
        // window, so that is where it goes.
        val parts = SharedTranslation.divide("绝对不要动，闭上眼睛", listOf(1, 1))
        assertEquals(listOf("绝对不要动，", "闭上眼睛"), parts)
    }

    @Test
    fun `a bigger balloon takes more of the line`() {
        val parts = SharedTranslation.divide("一二三四五六七八九十", listOf(8, 2))
        assertTrue(parts[0].length > parts[1].length, "got $parts")
        assertEquals("一二三四五六七八九十", parts.joinToString(""))
    }

    @Test
    fun `more balloons than characters leaves the last ones blank`() {
        // A covered, empty balloon beats leaving the original showing under a
        // sentence that has moved to its neighbour.
        val parts = SharedTranslation.divide("好", listOf(1, 1, 1))
        assertEquals(3, parts.size)
        assertEquals("好", parts.joinToString(""))
    }

    @Test
    fun `an empty translation stays empty everywhere`() {
        assertEquals(listOf("", ""), SharedTranslation.divide("", listOf(1, 1)))
    }

    @Test
    fun `partOf agrees with divide`() {
        val weights = listOf(4, 6)
        val text = "看着我的眼睛"
        val divided = SharedTranslation.divide(text, weights)
        for (index in weights.indices) {
            assertEquals(divided[index], SharedTranslation.partOf(text, weights, index))
        }
        assertEquals("", SharedTranslation.partOf(text, weights, 5))
    }
}
