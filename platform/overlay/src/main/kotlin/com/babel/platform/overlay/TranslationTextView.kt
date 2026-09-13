package com.babel.platform.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.babel.core.model.RenderedTranslation
import com.babel.domain.vision.ColorAnalysis

/**
 * One translated line, drawn over the original.
 *
 * The goal is that the user sees the original text having become the
 * translation — not a panel sitting on top of it. That means an opaque
 * background, bounds matching the source exactly, and text scaled to fit rather
 * than overflowing.
 *
 * Colours come from the source when the acquisition method could see them.
 * OCR can — it holds the pixels — and a bubble painted its own white reads as
 * the original text having changed. Accessibility cannot: `AccessibilityNodeInfo`
 * exposes neither text size nor colours, so that path still falls back to the
 * system light/dark setting, and its overlays never quite match the app
 * underneath.
 */
internal class TranslationTextView(context: Context) : TextView(context), TranslationView {

    override val view: View get() = this

    private val isDarkTheme: Boolean
        get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    init {
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        includeFontPadding = false
        setPadding(HORIZONTAL_PADDING_PX, 0, HORIZONTAL_PADDING_PX, 0)
        applyTheme()
    }

    override fun bind(translation: RenderedTranslation) {
        applyColors(translation)
        applyGravity(translation)
        text = translation.text
        configureAutoSize(translation)
    }

    /**
     * Lettering sits in the middle of a speech bubble, so a translation given a
     * bubble to fill is centred in it.
     *
     * Only where the bounds really are a bubble. The V1 path is handed the
     * source node's own box, which sits inside a laid-out screen — centring
     * there would shift text away from the words it replaces. A sampled
     * background is what distinguishes the two: only the OCR path has one, and
     * only the OCR path grows its box out to the bubble.
     */
    private fun applyGravity(translation: RenderedTranslation) {
        gravity = if (translation.style.sourceStyle.backgroundColor != null) {
            Gravity.CENTER
        } else {
            Gravity.CENTER_VERTICAL or Gravity.START
        }
    }

    /**
     * Sampled colours beat the theme whenever they exist.
     *
     * The device's dark mode says nothing about the page being translated: a
     * comic is white regardless, and V2's first end-to-end run put dark grey
     * overlays on a white page for exactly this reason.
     */
    private fun applyColors(translation: RenderedTranslation) {
        val sampled = translation.style.sourceStyle.backgroundColor
        if (sampled == null) {
            applyTheme()
            return
        }

        setBackgroundColor(sampled)
        // The sampled ink is preferred, but only as a colour — not as a
        // guarantee of contrast. Where it is missing, brightness of the
        // background decides, which is always readable even if less faithful.
        setTextColor(
            translation.style.sourceStyle.foregroundColor
                ?: if (ColorAnalysis.luminance(sampled) < MID_LUMINANCE) Color.WHITE else Color.BLACK,
        )
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
     * A translation is often longer than its source, so the text shrinks to fit
     * rather than spilling outside the original bounds.
     *
     * The height of the source bounds is **not** a line height: a paragraph
     * four lines tall reports the height of all four. Deriving a text size
     * straight from it produced 160px type for an ordinary paragraph, and a
     * container node reporting the height of an entire WebView produced 600px
     * type covering half the screen.
     *
     * So the height only bounds how many lines fit; the type size is capped
     * absolutely, and autosizing picks the largest size that actually fits the
     * box.
     */
    private fun configureAutoSize(translation: RenderedTranslation) {
        val heightPx = translation.bounds.height
        val requested = translation.style.preferredTextSizeSp

        val maxSizePx = if (requested != null) {
            spToPx(requested).coerceAtLeast(minAutoSizePx() + 1)
        } else {
            TextFitting.maxTextSizePx(
                boundsHeightPx = heightPx,
                glyphHeightRatio = GLYPH_HEIGHT_RATIO,
                ceilingPx = spToPx(MAX_TEXT_SIZE_SP),
                floorPx = minAutoSizePx(),
            )
        }

        maxLines = translation.style.maxLines ?: TextFitting.maxLines(
            boundsHeightPx = heightPx,
            minTextSizePx = minAutoSizePx(),
            lineSpacingRatio = LINE_SPACING_RATIO,
        )

        setAutoSizeTextTypeUniformWithConfiguration(
            minAutoSizePx(),
            maxSizePx,
            AUTO_SIZE_STEP_PX,
            TypedValue.COMPLEX_UNIT_PX,
        )
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
        /** Midpoint of 0..255 brightness: below it, white text reads better. */
        const val MID_LUMINANCE = 128.0

        const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()

        const val GLYPH_HEIGHT_RATIO = 0.7f
        const val LINE_SPACING_RATIO = 1.2f
        const val MIN_TEXT_SIZE_SP = 8f
        const val DEFAULT_TEXT_SIZE_SP = 14f

        /**
         * Nothing on a page is legitimately larger than this, and without the
         * cap a container node's height turns into display-sized type.
         */
        const val MAX_TEXT_SIZE_SP = 24f
        const val AUTO_SIZE_STEP_PX = 1
        const val HORIZONTAL_PADDING_PX = 2
    }
}
