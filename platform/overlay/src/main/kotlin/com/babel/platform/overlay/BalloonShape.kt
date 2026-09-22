package com.babel.platform.overlay

/**
 * How far to round the corners of a translation drawn over a speech balloon.
 *
 * A balloon is an oval; a translation was a sharp rectangle sized to the
 * lettering it replaces and then grown. The corners of that rectangle sit
 * outside the oval, which is the overhang visible on real pages — clearest on
 * `jap-mag-08`, mild on 02 and 03.
 *
 * Rounding is an approximation and deliberately so. The renderer does not know
 * the balloon's shape, or even its box: `TextRegion.enclosure` stops in the
 * domain and never reaches `RenderedTranslation`. Fitting an ellipse would need
 * that box carried down, and an ellipse inscribed in these bounds has around a
 * fifth less area than the rectangle — enough to let the original lettering show
 * at the edges. Pulling the corners in costs almost none of the cover and takes
 * away most of the overhang.
 *
 * Proportional to the shorter side so a small balloon is not rounded into a
 * lozenge, and capped so a long thin one keeps a recognisable box.
 */
internal object BalloonShape {

    private const val SHORT_SIDE_FRACTION = 0.22f
    private const val MAX_RADIUS_PX = 48f

    fun cornerRadius(width: Int, height: Int): Float {
        val shortest = minOf(width, height).toFloat()
        if (shortest <= 0f) return 0f
        return minOf(shortest * SHORT_SIDE_FRACTION, MAX_RADIUS_PX)
    }
}
