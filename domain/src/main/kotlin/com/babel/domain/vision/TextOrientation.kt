package com.babel.domain.vision

import com.babel.core.model.TextBounds
import kotlin.math.abs

/**
 * How a line of text is set, which determines both how lines group into bubbles
 * and the order they read in.
 *
 * A page mixes both — vertical dialogue beside horizontal captions or sound
 * effects — so this belongs to each line, never to the page.
 */
enum class TextOrientation {
    /** Characters run top to bottom; columns are ordered right to left (CJK). */
    VERTICAL,

    /** Characters run left to right; lines are ordered top to bottom. */
    HORIZONTAL,
}

/**
 * Works out how a line is set.
 *
 * Prefer [fromAngle]: ML Kit reports a line angle, and measured on vertical
 * Japanese it came back as 88.6°–92.0° — an unambiguous signal that needs no
 * guessing. [fromBounds] exists for recognisers that report no angle, and is
 * strictly weaker: a single-character line is square and tells us nothing,
 * whereas an angle is just as definite there.
 */
object TextOrientationDetector {

    /**
     * @param degrees line angle as the engine reports it, in degrees.
     */
    fun fromAngle(degrees: Float): TextOrientation {
        // Fold onto [0,180): a line at 270° reads the same way as one at 90°.
        val normalised = ((degrees % HALF_TURN) + HALF_TURN) % HALF_TURN
        return if (normalised in VERTICAL_LOWER..VERTICAL_UPPER) {
            TextOrientation.VERTICAL
        } else {
            TextOrientation.HORIZONTAL
        }
    }

    /**
     * Shape-based fallback. Returns null when the box is too close to square to
     * call — a single character, typically — leaving the caller to decide.
     *
     * @param minRatio how much longer one side must be to be conclusive.
     */
    fun fromBounds(bounds: TextBounds, minRatio: Float = DEFAULT_MIN_RATIO): TextOrientation? {
        val width = bounds.width
        val height = bounds.height
        if (width <= 0 || height <= 0) return null

        return when {
            height >= width * minRatio -> TextOrientation.VERTICAL
            width >= height * minRatio -> TextOrientation.HORIZONTAL
            else -> null
        }
    }

    /** True when an angle sits near the boundary, where the call is unreliable. */
    fun isAngleAmbiguous(degrees: Float, marginDegrees: Float = DEFAULT_ANGLE_MARGIN): Boolean {
        val normalised = ((degrees % HALF_TURN) + HALF_TURN) % HALF_TURN
        return abs(normalised - VERTICAL_LOWER) < marginDegrees ||
            abs(normalised - VERTICAL_UPPER) < marginDegrees
    }

    private const val HALF_TURN = 180f
    private const val VERTICAL_LOWER = 45f
    private const val VERTICAL_UPPER = 135f
    private const val DEFAULT_MIN_RATIO = 1.5f
    private const val DEFAULT_ANGLE_MARGIN = 10f
}
