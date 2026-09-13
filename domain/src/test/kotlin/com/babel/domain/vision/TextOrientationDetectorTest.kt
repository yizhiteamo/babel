package com.babel.domain.vision

import com.babel.core.model.CoordinateSpace
import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TextOrientationDetectorTest {

    private fun bounds(left: Int, top: Int, right: Int, bottom: Int) =
        TextBounds(left, top, right, bottom, CoordinateSpace.SCREEN)

    /**
     * The angles ML Kit actually reported for the vertical Japanese sample.
     * They cluster near 90° but are not exactly 90°, which is why the decision
     * is a range rather than an equality.
     */
    private val measuredVerticalAngles =
        listOf(91.054344f, 90.6045f, 91.9741f, 90.07185f, 88.64869f, 89.907455f, 90.11444f)

    @Test
    fun `every measured angle reads as vertical`() {
        measuredVerticalAngles.forEach { angle ->
            assertEquals(
                TextOrientation.VERTICAL,
                TextOrientationDetector.fromAngle(angle),
                "angle $angle should be vertical",
            )
        }
    }

    @Test
    fun `level text reads as horizontal`() {
        listOf(0f, 1.5f, -2f, 179f).forEach { angle ->
            assertEquals(TextOrientation.HORIZONTAL, TextOrientationDetector.fromAngle(angle))
        }
    }

    /** 270° is the same axis as 90°; an engine may report either. */
    @Test
    fun `angles outside one half turn are folded`() {
        assertEquals(TextOrientation.VERTICAL, TextOrientationDetector.fromAngle(270f))
        assertEquals(TextOrientation.VERTICAL, TextOrientationDetector.fromAngle(-90f))
        assertEquals(TextOrientation.HORIZONTAL, TextOrientationDetector.fromAngle(360f))
    }

    @Test
    fun `angles near the boundary are flagged as unreliable`() {
        assertTrue(TextOrientationDetector.isAngleAmbiguous(44f))
        assertTrue(TextOrientationDetector.isAngleAmbiguous(137f))
        assertFalse(TextOrientationDetector.isAngleAmbiguous(90f))
        assertFalse(TextOrientationDetector.isAngleAmbiguous(2f))
    }

    @Test
    fun `a tall box reads as vertical and a wide one as horizontal`() {
        // Measured column: 36x155.
        assertEquals(TextOrientation.VERTICAL, TextOrientationDetector.fromBounds(bounds(1157, 127, 1193, 282)))
        assertEquals(TextOrientation.HORIZONTAL, TextOrientationDetector.fromBounds(bounds(100, 100, 500, 140)))
    }

    /**
     * The gap shape-based detection cannot close, and the reason the engine's
     * angle is preferred: one character is square.
     */
    @Test
    fun `a square box is inconclusive by shape`() {
        assertNull(TextOrientationDetector.fromBounds(bounds(500, 200, 536, 236)))
    }

    @Test
    fun `a degenerate box is inconclusive`() {
        assertNull(TextOrientationDetector.fromBounds(bounds(50, 50, 50, 50)))
        assertNull(TextOrientationDetector.fromBounds(bounds(50, 50, 40, 60)))
    }
}
