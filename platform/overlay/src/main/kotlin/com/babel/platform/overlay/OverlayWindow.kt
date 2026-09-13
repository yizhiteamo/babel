package com.babel.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.babel.core.model.RenderedTranslation
import com.babel.core.model.TextElementId
import com.babel.core.model.TextOrientation

/**
 * Owns the window translations are drawn into.
 *
 * The container is laid out over the whole display and is entirely
 * non-interactive: `FLAG_NOT_TOUCHABLE` means every touch reaches the app
 * underneath, so translating a screen never changes how it behaves. That is a
 * V1 acceptance condition, and it is also why translations cannot be tappable.
 *
 * All methods must run on the main thread — [OverlayRenderer] guarantees this.
 */
internal class OverlayWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var container: FrameLayout? = null
    private val views = LinkedHashMap<TextElementId, TranslationView>()
    private val coordinateMapper = CoordinateMapper()

    val isAttached: Boolean get() = container != null

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /** Returns false when the overlay permission is not granted. */
    fun attach(): Boolean {
        if (container != null) return true
        if (!canDraw()) return false

        val layout = FrameLayout(context)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // Must be fully opaque, or the original text shows through the
            // translation and the two render on top of each other. Some
            // systems default overlay windows to a reduced alpha.
            alpha = 1f
        }

        windowManager.addView(layout, params)
        container = layout
        refreshContainerOrigin(layout)
        return true
    }

    fun detach() {
        val layout = container ?: return
        views.clear()
        layout.removeAllViews()
        runCatching { windowManager.removeView(layout) }
        container = null
    }

    fun show(translations: List<RenderedTranslation>) {
        val layout = container ?: return
        refreshContainerOrigin(layout)

        for (translation in translations) {
            val bounds = coordinateMapper.toRenderSpace(translation.bounds)
            // Vertical dialogue gets a vertical translation, which is how
            // lettering looks and how the translation lands on the columns it
            // replaces instead of beside them (`docs/milestones/v2.md`).
            // Accessibility reports no orientation, so V1 always takes the
            // horizontal path.
            val wantsVertical = translation.style.sourceStyle.orientation == TextOrientation.VERTICAL

            val existing = views[translation.elementId]
            val view = if (existing != null && existing.isVertical == wantsVertical) {
                existing
            } else {
                // A changed writing mode needs a different view, not a rebind.
                existing?.let { layout.removeView(it.view) }
                newView(wantsVertical).also {
                    views[translation.elementId] = it
                    layout.addView(it.view)
                }
            }

            view.bind(translation)
            view.view.layoutParams = FrameLayout.LayoutParams(
                bounds.width.coerceAtLeast(1),
                bounds.height.coerceAtLeast(1),
            ).apply {
                leftMargin = bounds.left
                topMargin = bounds.top
            }
            view.view.visibility = View.VISIBLE
            view.view.requestLayout()
        }
    }

    fun hide(ids: List<TextElementId>) {
        val layout = container ?: return
        for (id in ids) {
            views.remove(id)?.let { layout.removeView(it.view) }
        }
    }

    private fun newView(vertical: Boolean): TranslationView =
        if (vertical) VerticalTranslationView(context) else TranslationTextView(context)

    private val TranslationView.isVertical: Boolean
        get() = this is VerticalTranslationView

    fun clear() {
        val layout = container ?: return
        views.clear()
        layout.removeAllViews()
    }

    /**
     * Re-read on every batch: the system may reposition the window across
     * rotation or an insets change, and a stale origin puts every translation
     * off by the height of the status bar.
     */
    private fun refreshContainerOrigin(layout: FrameLayout) {
        val location = IntArray(2)
        layout.getLocationOnScreen(location)
        coordinateMapper.updateContainerOrigin(location[0], location[1])
    }
}
