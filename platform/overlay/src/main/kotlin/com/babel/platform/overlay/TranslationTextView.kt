package com.babel.platform.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import com.babel.core.model.RenderedTranslation

/**
 * One translated line, drawn over the original.
 *
 * The goal is that the user sees the original text having become the
 * translation — not a panel sitting on top of it. That means an opaque
 * background, bounds matching the source exactly, and text scaled to fit rather
 * than overflowing.
 *
 * `AccessibilityNodeInfo` exposes neither the original text size nor its
 * colours, so size is inferred from the height of the source bounds and colours
 * follow the system light/dark setting. V1 rules out MediaProjection, so
 * sampling the real background is not available.
 */
internal class TranslationTextView(context: Context) : TextView(context) {

    private val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    init {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPadding(HORIZONTAL_PADDING_PX, 0, HORIZONTAL_PADDING_PX, 0)
        applyTheme()
    }

    fun bind(translation: RenderedTranslation) {
        applyTheme()
        text = translation.text
        configureAutoSize(translation)
    }

    /**
     * Android caps the opacity of a touch-passthrough overlay at 0.8
     * (`maximum_obscuring_opacity_for_touch`), so a fifth of whatever is
     * underneath always bleeds through. The final pixel is
     * `0.8 × ours + 0.2 × theirs`.
     *
     * That makes the background choice matter more than it looks: where the
     * translation is shorter than the source, the leftover strip shows
     * `0.8 × ours + 0.2 × their background`. Picking a colour close to a
     * typical app surface makes that strip blend in, while pure black or white
     * — the furthest extremes from most surfaces — makes it stand out as a
     * visible block. These are the Material surface colours for that reason.
     */
    private fun applyTheme() {
        if (isDarkTheme) {
            setBackgroundColor(DARK_SURFACE)
            setTextColor(Color.WHITE)
        } else {
            setBackgroundColor(LIGHT_SURFACE)
            setTextColor(Color.BLACK)
        }
    }

    /**
     * A translation is often longer than its source — Chinese into English
     * especially — so the text shrinks to fit rather than spilling outside the
     * original bounds and colliding with neighbouring content.
     */
    private fun configureAutoSize(translation: RenderedTranslation) {
        val heightPx = translation.bounds.height
        val requested = translation.style.preferredTextSizeSp
        val maxSizePx = when {
            requested != null -> spToPx(requested)
            // Glyphs occupy roughly 70% of a line box once leading is excluded.
            heightPx > 0 -> (heightPx * GLYPH_HEIGHT_RATIO).toInt()
            else -> spToPx(DEFAULT_TEXT_SIZE_SP)
        }.coerceAtLeast(minAutoSizePx() + 1)

        maxLines = translation.style.maxLines ?: estimateMaxLines(heightPx, maxSizePx)

        setAutoSizeTextTypeUniformWithConfiguration(
            minAutoSizePx(),
            maxSizePx,
            AUTO_SIZE_STEP_PX,
            TypedValue.COMPLEX_UNIT_PX,
        )
    }

    private fun estimateMaxLines(heightPx: Int, textSizePx: Int): Int {
        if (heightPx <= 0 || textSizePx <= 0) return 1
        return (heightPx / (textSizePx * LINE_SPACING_RATIO)).toInt().coerceAtLeast(1)
    }

    private fun minAutoSizePx(): Int = spToPx(MIN_TEXT_SIZE_SP)

    private fun spToPx(sp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp,
        resources.displayMetrics,
    ).toInt()

    private companion object {
        /** Material dark surface, close to what most dark-themed apps use. */
        const val DARK_SURFACE = 0xFF121212.toInt()

        /** Most light-themed apps sit on plain white. */
        const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()

        const val GLYPH_HEIGHT_RATIO = 0.7f
        const val LINE_SPACING_RATIO = 1.2f
        const val MIN_TEXT_SIZE_SP = 8f
        const val DEFAULT_TEXT_SIZE_SP = 14f
        const val AUTO_SIZE_STEP_PX = 1
        const val HORIZONTAL_PADDING_PX = 2
    }
}
