package com.babel.platform.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.babel.core.model.RenderedTranslation
import com.babel.core.model.RowGradient
import com.babel.domain.vision.ColorAnalysis

/**
 * A game's line, erased and written again in its place.
 *
 * The backdrop is painted back row by row from the colours either side of the
 * original ([RowGradient]), so the panel shows through where the text was; the
 * translation is then set in the original's ink colour, at the original's
 * height, from its left edge. A game cannot be asked to draw other words —
 * Babel does not touch the game — so this is how its text comes to read as
 * translated rather than covered (`docs/features/v3-game-translation.md`).
 *
 * Only for lines on a plain or graded panel; anything else keeps
 * [TranslationTextView]'s box.
 */
internal class ErasedTextView(context: Context) : TextView(context), TranslationView {

    override val view: View get() = this

    init {
        gravity = Gravity.TOP or Gravity.START
        includeFontPadding = false
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    override fun bind(translation: RenderedTranslation) {
        val style = translation.style.sourceStyle
        val backdrop = requireNotNull(style.backdrop) { "an erased line needs its backdrop" }
        background = GradientBackdrop(backdrop)

        // Along the line the margin is cleared space, and the text keeps the
        // original's left edge; up and down it is room the type needs — a
        // line is taller than its glyphs.
        val margin = backdrop.marginPx
        setPadding(margin, 0, margin, 0)

        val ink = style.foregroundColor ?: contrastingInk(backdrop)
        setTextColor(ink)
        // The original is drawn with a dark edge round light letters; without
        // one, light type on a mid-tone panel loses its outline and reads thin.
        val glyph = style.glyphSizePx ?: translation.bounds.height
        if (ColorAnalysis.luminance(ink) >= MID_LUMINANCE) {
            setShadowLayer(glyph * SHADOW_RATIO, 0f, 0f, darkened(backdrop.colorAt(backdrop.rows / 2, 0.5f)))
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }

        text = translation.text
        configureSize(glyph, translation.bounds.height)
    }

    /**
     * The original's size, wrapped where the box has room for another line,
     * and shrunk only as far as [MIN_SIZE_RATIO] of it — further and it stops
     * reading as the game's own text; past that it is cut with "…".
     */
    private fun configureSize(glyphPx: Int, roomPx: Int) {
        val size = (glyphPx / GLYPH_TO_TEXT_SIZE).toInt().coerceAtLeast(MIN_TEXT_PX + 1)
        val smallest = (size * MIN_SIZE_RATIO).toInt().coerceIn(MIN_TEXT_PX, size - 1)
        maxLines = (roomPx / (smallest * LINE_SPACING * FONT_LINE_HEIGHT)).toInt().coerceAtLeast(1)
        setLineSpacing(0f, if (maxLines == 1) 1f else LINE_SPACING)
        // One line sits where the original's did, in the middle of its box:
        // set from the top, a smaller affiliation beside a larger name came
        // out low, across the rule under the name.
        gravity = if (maxLines == 1) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.TOP or Gravity.START
        setAutoSizeTextTypeUniformWithConfiguration(smallest, size, 1, TypedValue.COMPLEX_UNIT_PX)
    }

    private fun contrastingInk(backdrop: RowGradient): Int =
        if (ColorAnalysis.luminance(backdrop.colorAt(backdrop.rows / 2, 0.5f)) < MID_LUMINANCE) Color.WHITE else Color.BLACK

    private fun darkened(color: Int): Int =
        Color.rgb(Color.red(color) / 2, Color.green(color) / 2, Color.blue(color) / 2)

    /**
     * The backdrop drawn once into a narrow bitmap and stretched: each row's
     * two ends interpolated across [COLUMNS] steps, which filtering smooths
     * the rest of the way. Cheaper than a shader per row on every draw.
     */
    private class GradientBackdrop(gradient: RowGradient) : Drawable() {
        private val bitmap: Bitmap = Bitmap.createBitmap(COLUMNS, gradient.rows.coerceAtLeast(1), Bitmap.Config.ARGB_8888).apply {
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = gradient.colorAt(y, x / (width - 1f))
                }
            }
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val source = Rect(0, 0, bitmap.width, bitmap.height)

        override fun draw(canvas: Canvas) {
            canvas.drawBitmap(bitmap, source, bounds, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.OPAQUE
    }

    private companion object {
        const val COLUMNS = 64

        /** A CJK glyph's ink fills about this much of its text size. */
        const val GLYPH_TO_TEXT_SIZE = 0.88f

        /** A line of CJK type against its text size; the fallback font sets it. */
        const val FONT_LINE_HEIGHT = 1.45f
        const val LINE_SPACING = 1.1f

        const val MIN_SIZE_RATIO = 0.7f
        const val MIN_TEXT_PX = 12
        const val SHADOW_RATIO = 0.08f
        const val MID_LUMINANCE = 128.0
    }
}
