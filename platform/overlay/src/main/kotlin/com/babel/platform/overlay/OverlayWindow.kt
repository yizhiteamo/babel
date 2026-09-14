package com.babel.platform.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.babel.core.model.RenderedTranslation
import com.babel.core.model.TextElementId
import com.babel.core.model.TextOrientation

/**
 * Owns the windows translations are drawn into — **one window per translation**,
 * sized to the text it replaces.
 *
 * ## Why not one window over the whole screen
 *
 * It used to be exactly that, non-interactive, so every touch reached the app
 * underneath. The cost was that the original text always showed through at 20%:
 * Android caps the opacity of an untrusted, touch-passthrough overlay at
 * `maximum_obscuring_opacity_for_touch` (0.8 by default, and unset on the test
 * device so the default applies). We asked for `alpha = 1f` and `dumpsys`
 * reported `mAlpha=0.8` — the system clamped it. The cap exists to stop a
 * screen-covering window that cannot be touched from tricking the user.
 *
 * That reasoning does not apply to a window the size of a speech bubble. Each
 * translation now gets its own window which **does** take touches, so nothing
 * is obscured deceptively and the opacity stands: the original is genuinely
 * replaced rather than shining through.
 *
 * What it costs is honest and small: a touch that lands on a translation is
 * consumed rather than passed to the app. Everywhere else — the large majority
 * of the screen — behaves exactly as before, so page turns and scrolling still
 * work. Tapping a translation hides it for a moment, which is both the way out
 * of a swallowed tap and the obvious way to check the original.
 *
 * An earlier experiment removed `FLAG_NOT_TOUCHABLE` from the *full-screen*
 * window and left the screen unusable. That was the wrong experiment for this
 * question, not evidence against it.
 *
 * All methods must run on the main thread — [OverlayRenderer] guarantees this.
 */
internal class OverlayWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val views = LinkedHashMap<TextElementId, TranslationView>()
    private val coordinateMapper = CoordinateMapper()

    private var attached = false

    val isAttached: Boolean get() = attached

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    /**
     * There is no window to create up front any more; this only reports whether
     * windows may be added at all.
     */
    fun attach(): Boolean {
        if (attached) return true
        if (!canDraw()) return false
        // Windows are positioned in screen coordinates, so there is no
        // container whose origin could drift.
        coordinateMapper.updateContainerOrigin(0, 0)
        attached = true
        return true
    }

    fun detach() {
        clear()
        attached = false
    }

    fun show(translations: List<RenderedTranslation>) {
        if (!attached) return

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
                existing?.let { remove(it) }
                newView(wantsVertical).also { views[translation.elementId] = it }
            }

            view.bind(translation)

            val params = layoutParams(bounds.left, bounds.top, bounds.width, bounds.height)
            if (view.view.isAttachedToWindow) {
                runCatching { windowManager.updateViewLayout(view.view, params) }
            } else {
                runCatching { windowManager.addView(view.view, params) }
            }
            view.view.visibility = View.VISIBLE
        }
    }

    fun hide(ids: List<TextElementId>) {
        for (id in ids) {
            views.remove(id)?.let(::remove)
        }
    }

    fun clear() {
        views.values.forEach(::remove)
        views.clear()
    }

    private fun remove(view: TranslationView) {
        runCatching { windowManager.removeView(view.view) }
    }

    private fun newView(vertical: Boolean): TranslationView {
        val view = if (vertical) VerticalTranslationView(context) else TranslationTextView(context)
        // Tap to look underneath. A translation swallows the touch that lands on
        // it, so it has to offer something in return — and "show me the
        // original" is the thing a reader wants from it anyway.
        view.view.setOnClickListener { it.visibility = View.INVISIBLE }
        return view
    }

    private val TranslationView.isVertical: Boolean
        get() = this is VerticalTranslationView

    /**
     * Touchable on purpose — see the note on this class. `FLAG_NOT_FOCUSABLE`
     * stays: taking touches is not a reason to steal the keyboard.
     */
    private fun layoutParams(left: Int, top: Int, width: Int, height: Int) =
        WindowManager.LayoutParams(
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = top
            alpha = 1f
        }
}
