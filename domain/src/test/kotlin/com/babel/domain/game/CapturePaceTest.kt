package com.babel.domain.game

import com.babel.domain.game.CapturePace.Companion.ACTIVE_GAP_MS
import com.babel.domain.game.CapturePace.Companion.QUIET_AFTER_MS
import com.babel.domain.game.CapturePace.Companion.QUIET_GAP_MS
import com.babel.domain.game.CapturePace.Companion.STILL_AFTER_MS
import com.babel.domain.game.CapturePace.Companion.STILL_GAP_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapturePaceTest {

    private val pace = CapturePace()

    @Test
    fun `while text is moving pictures come as fast as the platform allows`() {
        assertEquals(ACTIVE_GAP_MS, pace.gapMs(0, ThermalLevel.NONE))
        assertEquals(ACTIVE_GAP_MS, pace.gapMs(QUIET_AFTER_MS - 1, ThermalLevel.NONE))
    }

    @Test
    fun `a screen still for a few seconds is looked at once a second`() {
        assertEquals(QUIET_GAP_MS, pace.gapMs(QUIET_AFTER_MS, ThermalLevel.NONE))
        assertEquals(QUIET_GAP_MS, pace.gapMs(STILL_AFTER_MS - 1, ThermalLevel.NONE))
    }

    @Test
    fun `a screen left alone is looked at least often`() {
        assertEquals(STILL_GAP_MS, pace.gapMs(STILL_AFTER_MS, ThermalLevel.NONE))
        assertEquals(STILL_GAP_MS, pace.gapMs(60 * 60_000L, ThermalLevel.NONE))
    }

    @Test
    fun `activity puts the pace straight back to the fastest`() {
        assertEquals(STILL_GAP_MS, pace.gapMs(STILL_AFTER_MS, ThermalLevel.NONE))
        assertEquals(ACTIVE_GAP_MS, pace.gapMs(0, ThermalLevel.NONE))
    }

    @Test
    fun `a warm device is paced one step slower`() {
        assertEquals(QUIET_GAP_MS, pace.gapMs(0, ThermalLevel.MODERATE))
        assertEquals(STILL_GAP_MS, pace.gapMs(QUIET_AFTER_MS, ThermalLevel.MODERATE))
        assertEquals(STILL_GAP_MS, pace.gapMs(STILL_AFTER_MS, ThermalLevel.MODERATE))
    }

    @Test
    fun `a device cutting its performance is paced as if the screen were still`() {
        assertEquals(STILL_GAP_MS, pace.gapMs(0, ThermalLevel.SEVERE))
    }

    @Test
    fun `the gap never shrinks as the screen stays still`() {
        for (heat in ThermalLevel.entries) {
            var last = -1L
            for (since in 0L..STILL_AFTER_MS * 2 step 500) {
                val gap = pace.gapMs(since, heat)
                assertTrue(gap >= last, "$heat at $since ms")
                last = gap
            }
        }
    }

    @Test
    fun `whole readings slow down with the pictures`() {
        assertEquals(CapturePace.ACTIVE_WHOLE_EVERY_MS, pace.wholeEveryMs(0, ThermalLevel.NONE))
        assertTrue(pace.wholeEveryMs(QUIET_AFTER_MS, ThermalLevel.NONE) > CapturePace.ACTIVE_WHOLE_EVERY_MS)
        assertTrue(pace.wholeEveryMs(STILL_AFTER_MS, ThermalLevel.NONE) > pace.wholeEveryMs(QUIET_AFTER_MS, ThermalLevel.NONE))
        assertEquals(pace.wholeEveryMs(STILL_AFTER_MS, ThermalLevel.NONE), pace.wholeEveryMs(0, ThermalLevel.SEVERE))
    }
}
