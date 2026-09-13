package com.babel.platform.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import com.babel.core.model.RenderedTranslation
import com.babel.domain.vision.ColorAnalysis

/**
 * A translation set in upright columns, reading right to left, the way the
 * Japanese it replaces was set.
 *
 * Self-drawn because Android has no vertical text: `TextView` has no writing
 * mode, and the layout classes lay glyphs out along a horizontal baseline.
 * Drawing one character at a time is the whole trick, and the arithmetic
 * deciding where they go lives in [VerticalTextLayout] where it can be tested.
 *
 * **Punctuation is not rotated.** Real vertical typesetting turns `ー`, `「`,
 * `」` and friends on their side. This does not, so those characters look wrong
 * — a known gap recorded in `docs/milestones/v2.md` rather than a thing to
 * discover later.
 */
internal class VerticalTranslationView(context: Context) : View(context), TranslationView {

    override val view: View get() = this

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var layout: VerticalTextLayout.Result? = null
    private var background = LIGHT_SURFACE

    override fun bind(translation: RenderedTranslation) {
        val style = translation.style.sourceStyle
        background = style.backgroundColor ?: LIGHT_SURFACE
        paint.color = style.foregroundColor
            ?: if (ColorAnalysis.luminance(background) < MID_LUMINANCE) Color.WHITE else Color.BLACK

        // Sized against the box the element actually occupies rather than the
        // view, because a view laid out moments ago may still report zero.
        val bounds = translation.bounds
        layout = VerticalTextLayout.layout(
            text = translation.text,
            boxWidthPx = bounds.width - HORIZONTAL_PADDING_PX * 2,
            boxHeightPx = bounds.height - VERTICAL_PADDING_PX * 2,
            maxGlyphPx = spToPx(MAX_GLYPH_SP),
            minGlyphPx = spToPx(MIN_GLYPH_SP),
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(background)

        val result = layout ?: return
        paint.textSize = result.glyphSizePx.toFloat()

        val pitch = result.columnPitchPx
        val advance = result.advancePx
        val metrics = paint.fontMetrics

        // Centred as a block, so a short translation sits in the middle of the
        // balloon the way lettering does rather than hugging a corner.
        val blockWidth = result.columns.size * pitch
        val rightEdge = (width + blockWidth) / 2f

        result.columns.forEachIndexed { index, column ->
            // First column rightmost: CJK columns run right to left, and
            // getting this backwards reverses the sentence.
            val centreX = rightEdge - (index + 0.5f) * pitch
            val columnHeight = column.length * advance
            var y = (height - columnHeight) / 2f - metrics.ascent

            column.forEach { character ->
                canvas.drawText(character.toString(), centreX, y, paint)
                y += advance
            }
        }
    }

    private fun spToPx(sp: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_SP,
        sp,
        resources.displayMetrics,
    ).toInt()

    private companion object {
        const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
        const val MID_LUMINANCE = 128.0
        const val HORIZONTAL_PADDING_PX = 4
        const val VERTICAL_PADDING_PX = 4

        /** Same ceiling the horizontal path uses, for the same reason. */
        const val MAX_GLYPH_SP = 24f
        const val MIN_GLYPH_SP = 8f
    }
}
