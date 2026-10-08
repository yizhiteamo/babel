package com.babel.domain.game

import com.babel.core.model.TextBounds
import com.babel.domain.acquisition.TextSource
import com.babel.domain.vision.ScreenCaptureController

/**
 * Turns game mode on and off. A type of its own, separate from manga mode's
 * controller, so the two can be injected side by side without a qualifier.
 */
interface GameCaptureController : ScreenCaptureController

/**
 * Reads one frame of a game's window and publishes the lines that have
 * settled.
 *
 * Free of Android types, like `ImageTextScanner`: the accessibility service
 * drives it without seeing the recogniser, and the pixels travel by
 * `ScreenFrameSource`.
 */
interface GameTextScanner : TextSource {

    /**
     * Reads the window [windowId] frame after frame for as long as
     * [stillWanted] says so, then returns.
     *
     * A loop rather than one frame per call, so the next picture can be taken
     * while the last one is being read: done one after the other, frames came
     * every ~470 ms instead of the platform's 333 (`docs/milestones/v3.md`).
     *
     * @param windowBounds where that window sits on the screen, so lines read
     *   in the window's own pixels can be put back in screen coordinates.
     */
    suspend fun read(windowId: Int, windowBounds: TextBounds, packageName: String, stillWanted: () -> Boolean)

    /** Takes everything this scanner published off the screen. */
    suspend fun clear()

    /** Lets go of loaded models; the next scan reloads them. */
    suspend fun release()
}

/** One line as the recogniser returned it, with what the frame says about it. */
data class GameLine(
    val text: String,
    val bounds: TextBounds,
    /**
     * How well the band above and below the line is predicted by interpolating,
     * row by row, between the pixels at its two ends. Low on a plain or evenly
     * graded panel, high on artwork (`docs/milestones/v3.md`, spike 2).
     */
    val backdropError: Double,
    /**
     * How tall the type itself is, from the ink: the recogniser's box for one
     * line of dialogue came back 56 px tall on one frame and 72 on the next,
     * and at 72 it was as tall as the speaker's name above it. Ruby is not
     * counted; it stands apart from the line it glosses.
     */
    val typeHeight: Int = bounds.height,
)
