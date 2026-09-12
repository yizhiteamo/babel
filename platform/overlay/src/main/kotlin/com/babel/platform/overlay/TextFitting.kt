package com.babel.platform.overlay

/**
 * Decides how translated text is sized to fit the space its source occupied.
 *
 * Pure arithmetic on purpose: this is where the sizing bugs were, and a
 * `TextView` cannot be constructed in a JVM unit test. Everything here works in
 * pixels; the view converts from sp.
 */
internal object TextFitting {

    /**
     * Largest type size to attempt.
     *
     * The height of the source bounds is **not** a line height — a paragraph
     * four lines tall reports all four, and a container node can report the
     * height of an entire WebView. Deriving a size straight from it produced
     * display-sized type across half the screen, so the result is capped.
     */
    fun maxTextSizePx(
        boundsHeightPx: Int,
        glyphHeightRatio: Float,
        ceilingPx: Int,
        floorPx: Int,
    ): Int {
        val fromHeight = if (boundsHeightPx > 0) {
            (boundsHeightPx * glyphHeightRatio).toInt()
        } else {
            ceilingPx
        }
        return minOf(fromHeight, ceilingPx).coerceAtLeast(floorPx + 1)
    }

    /**
     * How many lines the box could hold at the smallest readable size.
     *
     * Deliberately generous: capping it near one forces autosizing to squeeze a
     * whole paragraph onto a single line, which is what made translations
     * illegible on text-heavy pages.
     */
    fun maxLines(boundsHeightPx: Int, minTextSizePx: Int, lineSpacingRatio: Float): Int {
        if (boundsHeightPx <= 0 || minTextSizePx <= 0) return 1
        val smallestLineHeight = minTextSizePx * lineSpacingRatio
        return (boundsHeightPx / smallestLineHeight).toInt().coerceAtLeast(1)
    }
}
