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
import com.babel.core.model.TextSourceType

/**
 * Owns the windows translations are drawn into. There are **two arrangements**,
 * chosen by where the text came from, because the two jobs have opposite
 * requirements.
 *
 * ## Text in a live app (V1, accessibility)
 *
 * One window over the whole display with `FLAG_NOT_TOUCHABLE`, translations as
 * child views. Every touch reaches the app underneath, which is a V1 acceptance
 * condition: translating a screen must never change how it behaves.
 *
 * The price is that the original shows through at 20%. Android caps the opacity
 * of an untrusted overlay that covers the screen while letting touches past
 * (`maximum_obscuring_opacity_for_touch`, 0.8), and a window like this is
 * exactly what that rule is for. Here the cap is unavoidable, and worth paying.
 *
 * ## Text in a captured image (V2, manga)
 *
 * One window per translation, sized to the bubble, **taking touches**. Not
 * being a screen-covering passthrough window, it is not capped: `mAlpha` stays
 * 1.0 and the original is genuinely replaced rather than shining through.
 *
 * A touch landing on a translation is consumed instead of reaching the app.
 * That is affordable when reading static pages — everywhere else still passes
 * through, so page turns work — and tapping a translation hides it, which is
 * both the way out of a swallowed tap and the natural way to see the original.
 *
 * ## Why this is split rather than uniform
 *
 * It was uniform once, briefly, and it was a bug: applying the opaque touchable
 * treatment to V1 turned its overlays — which are often large, and sometimes
 * wrong, because a container node can report the size of a whole page — into
 * solid blocks that swallowed touches. Reported from a device within a day.
 *
 * All methods must run on the main thread — [OverlayRenderer] guarantees this.
 */
internal class OverlayWindow(private val context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Shared passthrough window for the accessibility path; created on demand. */
    private var container: FrameLayout? = null

    private val views = LinkedHashMap<TextElementId, Held>()
    private val coordinateMapper = CoordinateMapper()

    private var attached = false

    val isAttached: Boolean get() = attached

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun attach(): Boolean {
        if (attached) return true
        if (!canDraw()) return false
        attached = true
        return true
    }

    fun detach() {
        clear()
        container?.let { runCatching { windowManager.removeView(it) } }
        container = null
        attached = false
    }

    fun show(translations: List<RenderedTranslation>) {
        if (!attached) return

        for (translation in translations) {
            val ownWindow = translation.sourceType == TextSourceType.OCR
            if (!ownWindow && !ensureContainer()) continue

            // A window of its own is positioned on the display, so it wants the
            // screen coordinates as they came. A child of the container wants
            // them relative to wherever the system put that container — the two
            // differ by the status bar, and mixing them shifts every
            // translation by its height.
            val bounds = if (ownWindow) {
                translation.bounds
            } else {
                coordinateMapper.toRenderSpace(translation.bounds)
            }

            // Vertical dialogue gets a vertical translation, which is how
            // lettering looks and how the translation lands on the columns it
            // replaces instead of beside them (`docs/milestones/v2.md`).
            // Accessibility reports no orientation, so V1 stays horizontal.
            val vertical = translation.style.sourceStyle.orientation == TextOrientation.VERTICAL

            val existing = views[translation.elementId]
            val held = if (existing != null && existing.matches(vertical, ownWindow)) {
                existing
            } else {
                // A changed writing mode or a changed arrangement needs a new
                // view, not a rebind.
                existing?.let(::remove)
                newHeld(vertical, ownWindow).also { views[translation.elementId] = it }
            }

            // A fresh render is a fresh balloon: whatever the reader toggled or
            // dismissed belongs to the page that was on screen then.
            held.bound = translation
            held.showingOriginal = false
            held.dismissed = false
            held.rebind()
            place(held, bounds.left, bounds.top, bounds.width, bounds.height)
            held.view.view.visibility = View.VISIBLE
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

    // ------------------------------------------------------------- internals

    /**
     * A translation plus how it is being shown.
     *
     * [showingOriginal] and [dismissed] are **view state, not domain state**: a
     * page turn or a re-scan should bring the translation back, so [show]
     * resets both rather than the coordinator remembering them.
     */
    private class Held(val view: TranslationView, val ownWindow: Boolean) {
        var bound: RenderedTranslation? = null
        var showingOriginal = false
        var dismissed = false

        fun matches(vertical: Boolean, ownWindow: Boolean): Boolean =
            this.ownWindow == ownWindow && (view is VerticalTranslationView) == vertical

        /**
         * Rebinds with whichever text is wanted now.
         *
         * Swapping the text on the model rather than teaching the views about
         * two strings: both already lay out whatever they are given, so
         * vertical Japanese re-flows into its columns and the horizontal view
         * re-fits, for free.
         */
        fun rebind() {
            view.bind(bound?.showing(showingOriginal) ?: return)
        }
    }

    private fun newHeld(vertical: Boolean, ownWindow: Boolean): Held {
        val view: TranslationView =
            if (vertical) VerticalTranslationView(context) else TranslationTextView(context)
        val held = Held(view, ownWindow)

        if (ownWindow) {
            // A translation that swallows the touch landing on it has to offer
            // something back, and "show me the original" is what a reader wants
            // from it anyway.
            //
            // A *toggle* rather than the hide this used to do. Hiding was
            // one-way in practice: the view stops receiving touches once it is
            // INVISIBLE, and the re-render that would have restored it never
            // comes on a page that is not changing — `CaptureTextSource` keeps
            // reporting the same page and skipping.
            view.view.setOnClickListener {
                held.showingOriginal = !held.showingOriginal
                held.rebind()
            }

            // The other half of what hiding used to cover: getting out of the
            // way. Measured before changing it — the window stays touchable
            // with an invisible child, so the balloon's area became a dead spot
            // that neither answered taps nor let them reach the page. Going
            // away has to mean going away.
            view.view.setOnLongClickListener {
                held.dismissed = true
                it.visibility = View.INVISIBLE
                repositionDismissed(held)
                true
            }
        }
        return held
    }

    /** Re-lays the window as a passthrough, so a dismissed balloon is not a hole. */
    private fun repositionDismissed(held: Held) {
        val view = held.view.view
        if (!view.isAttachedToWindow) return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun place(held: Held, left: Int, top: Int, width: Int, height: Int) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)

        if (!held.ownWindow) {
            val layout = container ?: return
            if (held.view.view.parent == null) layout.addView(held.view.view)
            held.view.view.layoutParams = FrameLayout.LayoutParams(w, h).apply {
                leftMargin = left
                topMargin = top
            }
            held.view.view.requestLayout()
            return
        }

        val params = ownWindowParams(left, top, w, h)
        if (held.view.view.isAttachedToWindow) {
            runCatching { windowManager.updateViewLayout(held.view.view, params) }
        } else {
            runCatching { windowManager.addView(held.view.view, params) }
        }
    }

    private fun remove(held: Held) {
        if (held.ownWindow) {
            runCatching { windowManager.removeView(held.view.view) }
        } else {
            container?.removeView(held.view.view)
        }
    }

    /**
     * The passthrough window for the accessibility path, created the first time
     * that path renders anything.
     */
    private fun ensureContainer(): Boolean {
        container?.let {
            refreshContainerOrigin(it)
            return true
        }
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
            // Asked for, not granted: the system clamps this to 0.8 for a
            // passthrough window of this size, which is where V1's ghost comes
            // from. Left at 1f so the intent is on the record.
            alpha = 1f
        }

        runCatching { windowManager.addView(layout, params) }.onFailure { return false }
        container = layout
        refreshContainerOrigin(layout)
        return true
    }

    private fun ownWindowParams(left: Int, top: Int, width: Int, height: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable on purpose — see the note on this class. NOT_FOCUSABLE
            // stays: taking touches is not a reason to steal the keyboard.
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
