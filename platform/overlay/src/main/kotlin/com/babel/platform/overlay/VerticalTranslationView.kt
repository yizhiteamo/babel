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
    private val backgroundPaint = Paint().apply { isAntiAlias = true }

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
            boxWidthPx = bounds.width - VerticalTextLayout.HORIZONTAL_PADDING_PX * 2,
            boxHeightPx = bounds.height - VerticalTextLayout.VERTICAL_PADDING_PX * 2,
            maxGlyphPx = spToPx(VerticalTextLayout.MAX_GLYPH_SP),
            minGlyphPx = spToPx(VerticalTextLayout.MIN_GLYPH_SP),
        )
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Nothing to say, so nothing is covered — the background is what hides
        // the original, and hiding it to show nothing leaves the reader with
        // neither. A reported defect: `VerticalTextLayout.layout` returns null
        // when even the smallest type will not fit, and this used to paint the
        // balloon anyway and then return.
        //
        // The chooser in `OverlayWindow` now sets such a balloon horizontally
        // instead, so this is the net rather than the cure.
        //
        // Rounded rather than filled to the corners: a balloon is an oval, and
        // a rectangle's corners sit outside it ([BalloonShape]).
        val result = layout ?: return

        backgroundPaint.color = background
        val radius = BalloonShape.cornerRadius(width, height)
        canvas.drawRoundRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            radius,
            radius,
            backgroundPaint,
        )

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

        /** Same ceiling the horizontal path uses, for the same reason. */

    }
}
