package com.babel.domain.game

import javax.inject.Inject

/** How hot the device says it is, from the platform's thermal status. */
enum class ThermalLevel {
    /** Nothing to report, or the platform's "light". */
    NONE,

    /** The platform's "moderate": the device has begun to hold itself back. */
    MODERATE,

    /** "Severe" or worse: performance is being cut hard. */
    SEVERE,
}

/**
 * How long game mode waits between pictures of a game's window.
 *
 * A still screen costs as much as a busy one when every picture is taken:
 * measured on Blue Archive's student list (`docs/milestones/v3.md`, "Throttling
 * when still"), 171 pictures a minute, not one of them read by the recogniser,
 * and still about 37% of one core — the screenshot's copy, the look for
 * replaced lines, and the reading that finds nothing new. So the pace follows
 * how long ago anything happened: the platform's limit while text is moving,
 * slower once the screen has been still a while.
 *
 * What counts as something happening is the caller's: a reading that found a
 * change, a translation withdrawn or moved, a tap handed on to the game.
 * Slowed down, a change is found at most one gap late, and the first change
 * puts the pace straight back to the fastest.
 */
class CapturePace @Inject constructor() {

    /**
     * The least time from one picture's start to the next's, in ms. A device
     * that reports itself warm is paced one step slower; one that is cutting
     * its performance hard, as if the screen were still.
     */
    fun gapMs(sinceActivityMs: Long, heat: ThermalLevel): Long = GAPS_MS[step(sinceActivityMs, heat)]

    /**
     * The longest between two readings of the whole picture, in ms. Moving
     * art is left unread between them, so on a screen whose art never stops
     * — a student over a waterfall — they are most of the recogniser's work:
     * slower with the pictures, they were 27 of 63 readings a minute.
     */
    fun wholeEveryMs(sinceActivityMs: Long, heat: ThermalLevel): Long = WHOLE_EVERY_MS[step(sinceActivityMs, heat)]

    private fun step(sinceActivityMs: Long, heat: ThermalLevel): Int {
        val step = when {
            sinceActivityMs >= STILL_AFTER_MS -> 2
            sinceActivityMs >= QUIET_AFTER_MS -> 1
            else -> 0
        }
        val hotter = when (heat) {
            ThermalLevel.NONE -> step
            ThermalLevel.MODERATE -> step + 1
            ThermalLevel.SEVERE -> 2
        }
        return hotter.coerceAtMost(GAPS_MS.lastIndex)
    }

    companion object {
        /** No wait of game mode's own: the platform allows a picture every ~333 ms. */
        const val ACTIVE_GAP_MS = 0L

        /**
         * After a few seconds without change: a line typed out and the player
         * reading it. A new one is found at most a second late.
         */
        const val QUIET_AFTER_MS = 3_000L
        const val QUIET_GAP_MS = 1_000L

        /** After twenty seconds: a menu or a screen left alone. */
        const val STILL_AFTER_MS = 20_000L
        const val STILL_GAP_MS = 2_000L

        private val GAPS_MS = longArrayOf(ACTIVE_GAP_MS, QUIET_GAP_MS, STILL_GAP_MS)

        /** Whole readings: every 4 s while text moves, as before there was a pace; then 8 s, then 16 s. */
        const val ACTIVE_WHOLE_EVERY_MS = 4_000L
        private val WHOLE_EVERY_MS = longArrayOf(ACTIVE_WHOLE_EVERY_MS, 8_000L, 16_000L)
    }
}
