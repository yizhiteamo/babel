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
 * Android caps the opacity of an **untrusted** overlay that lets touches past
 * (`maximum_obscuring_opacity_for_touch`, 0.8), and for a long time the
 * original showed through every V1 translation at 20%. The container is now
 * added through the accessibility service as `TYPE_ACCESSIBILITY_OVERLAY`,
 * which the input system trusts: measured on a phone, `alpha=1` with
 * `FLAG_NOT_TOUCHABLE`, the original fully hidden, and a swipe starting on a
 * translation still scrolled the app (`TrustedWindows`). Without the service
 * it falls back to the capped app overlay.
 *
 * ## Text in a captured image (V2, manga)
 *
 * One window per translation, sized to the bubble, **taking touches**. A
 * touchable window is not capped: `mAlpha` stays 1.0 and the original is
 * genuinely replaced rather than shining through.
 *
 * A touch landing on a translation is consumed instead of reaching the app.
 * Tapping shows the original, which is both the way out of a swallowed tap and
 * the natural way to check a translation against the art.
 *
 * ## The cost of that, measured, and why it stands anyway
 *
 * A window that takes touches owns the gesture from `ACTION_DOWN`, so a scroll
 * beginning on a translation never reaches the page and **the page does not
 * move at all**. On a webtoon that is not rare: nine translation windows,
 * **9.3%** of the screen, and **10.5%** of the start points a thumb actually
 * uses. One swipe in ten does nothing.
 *
 * Making these passthrough fixes it completely — and was tried, and reverted.
 * The cap turns out to be about **touchability, not size**, which is what the
 * note above used to say: the moment `FLAG_NOT_TOUCHABLE` went on, `dumpsys`
 * reported `alpha=0.7998` on every balloon window instead of 1.0, and the
 * Japanese was plainly legible underneath the Chinese. That is the 20% ghost
 * ADR 008's amendment exists to have removed.
 *
 * Nothing recovers both. Painting the view opaque cannot help — the clamp is
 * applied when the window is composited. Stacking two 0.8 windows reaches 0.96
 * and trips the *other* half of the same rule: combined obscuring opacity above
 * the threshold makes the system **block** touches to the app below, silently.
 *
 * So the swallowed swipe is the accepted cost, for now, and it is a product
 * judgement rather than an oversight. [OWN_WINDOW_FLAGS] and its test are where
 * that judgement is pinned.
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
/**
 * The V1 container: one window over the whole display, letting every touch
 * through. Passthrough is a V1 acceptance condition — translating a screen must
 * never change how it behaves — and it was broken exactly once, by giving V1
 * the manga treatment, which turned page-sized overlays into solid blocks.
 */
internal const val CONTAINER_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

/**
 * A single manga translation's window — **touchable on purpose**.
 *
 * Deliberately missing `FLAG_NOT_TOUCHABLE`. Adding it fixes the swallowed
 * scroll and costs the 20% ghost; both halves are measured on the note above.
 * Anything that adds it has to answer for the ghost first.
 */
internal const val OWN_WINDOW_FLAGS =
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

internal class OverlayWindow(
    private val context: Context,
    private val trusted: com.babel.platform.screen.TrustedWindows? = null,
    private val taps: com.babel.platform.screen.TapForwarder? = null,
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Shared passthrough window for the accessibility path; created on demand. */
    private var container: FrameLayout? = null

    /** The window manager [container] was added through, to remove it by. */
    private var containerManager: WindowManager? = null

    private val views = LinkedHashMap<TextElementId, Held>()
    private val coordinateMapper = CoordinateMapper()

    private var attached = false

    val isAttached: Boolean get() = attached

    fun canDraw(): Boolean = Settings.canDrawOverlays(context)

    fun attach(): Boolean {
        if (attached) return true
        // A connected service draws without the permission (trusted windows).
        if (!canDraw() && trusted?.context == null) return false
        attached = true
        return true
    }

    /**
     * A game's translation: a short tap goes on to the game, a long press
     * switches between the translation and the original
     * (`docs/features/v3-game-translation.md`).
     *
     * A switch rather than a hold: held, the finger covered the line it was
     * meant to show, and a long line could not be read while holding. The
     * whole screen switches together — a name in Japanese over a translated
     * line reads as a fault — and comes back by itself with the next line
     * ([show]), or by another long press, on the original where the
     * translation was.
     *
     * The window turns untouchable **at touch-down**. The rest of a touch that
     * began on a window still comes to that window, so press and release are
     * both seen here; only the tap handed on afterwards falls through. The
     * change takes ~80 ms to reach the input system, and a tap injected
     * sooner landed on this window again and was handed on twice - the game
     * skipped a line (`docs/milestones/v3.md`, spike 3). So the tap waits until
     * [UNTOUCHABLE_SETTLE_MS] after touch-down, which an ordinary finger tap
     * already lasts.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun passTapsThrough(held: Held) {
        val view = held.view.view
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var downAt = 0L
        var longPressed = false
        // A drag that began on a translation — scrolling a game's history —
        // used to go nowhere: only taps were handed on. Its points are kept
        // and the whole drag is handed on when the finger lifts.
        var dragging = false
        val points = mutableListOf<Pair<Float, Float>>()
        val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        val switch = Runnable {
            longPressed = true
            // Touchable again at once: an untouchable overlay is held to 80%
            // opacity, and for the rest of the hold the original showed
            // through the translation (measured on Fate/Grand Order). Nothing
            // is handed on after a long press, so nothing needs to fall through.
            setTouchable(view, true)
            showGameOriginal(!gameShowingOriginal)
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    downAt = android.os.SystemClock.uptimeMillis()
                    longPressed = false
                    dragging = false
                    points.clear()
                    points += event.rawX to event.rawY
                    setTouchable(view, false)
                    handler.postDelayed(switch, LONG_PRESS_MS)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    if (!longPressed) {
                        val (x0, y0) = points.first()
                        if (!dragging && kotlin.math.hypot(event.rawX - x0, event.rawY - y0) > slop) {
                            dragging = true
                            handler.removeCallbacks(switch)
                        }
                        if (dragging) points += event.rawX to event.rawY
                    }
                }
                android.view.MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(switch)
                    if (longPressed) {
                        setTouchable(view, true)
                    } else if (dragging) {
                        points += event.rawX to event.rawY
                        val duration = android.os.SystemClock.uptimeMillis() - downAt
                        val path = points.toList()
                        val forwarder = taps
                        if (forwarder == null || !forwarder.canForward) {
                            setTouchable(view, true)
                        } else {
                            forwarder.drag(path, duration) { view.postDelayed({ setTouchable(view, true) }, RETOUCHABLE_AFTER_MS) }
                        }
                    } else {
                        val x = event.rawX
                        val y = event.rawY
                        val wait = (UNTOUCHABLE_SETTLE_MS - (android.os.SystemClock.uptimeMillis() - downAt))
                            .coerceAtLeast(0)
                        handler.postDelayed({ forward(view, x, y) }, wait)
                    }
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(switch)
                    setTouchable(view, true)
                }
            }
            true
        }
    }

    /** Whether the game's translations are standing aside for the original. */
    private var gameShowingOriginal = false

    private fun showGameOriginal(on: Boolean) {
        gameShowingOriginal = on
        for (held in views.values) {
            if (!held.game) continue
            held.showingOriginal = on
            held.view.showOriginal(on)
        }
    }

    private fun forward(view: View, x: Float, y: Float) {
        val forwarder = taps
        if (forwarder == null || !forwarder.canForward) {
            setTouchable(view, true)
            return
        }
        forwarder.tap(x, y) {
            view.postDelayed({ setTouchable(view, true) }, RETOUCHABLE_AFTER_MS)
        }
    }

    private fun setTouchable(view: View, touchable: Boolean) {
        if (!view.isAttachedToWindow) return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.flags = if (touchable) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    fun detach() {
        clear()
        container?.let { runCatching { (containerManager ?: windowManager).removeView(it) } }
        container = null
        containerManager = null
        attached = false
    }

    fun show(translations: List<RenderedTranslation>) {
        if (!attached) return

        for (translation in translations) {
            // A game is full screen and landscape, where the container's offsets
            // do not hold; its lines get windows of their own, like manga mode's.
            val ownWindow = translation.sourceType == TextSourceType.OCR ||
                translation.sourceType == TextSourceType.GAME
            if (!ownWindow && !ensureContainer()) continue
            val game = translation.sourceType == TextSourceType.GAME

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
            //
            // **Unless it will not fit.** Vertical setting runs out of room
            // sooner than horizontal — a quarter of the box goes to the gaps
            // between columns — and when it does, `VerticalTextLayout` has
            // always said the caller should set the line horizontally instead.
            // Nothing ever did, so the view painted its sampled background and
            // then drew no text: a balloon-shaped blank covering the artwork,
            // reported from a device. Measured there, at 560dpi: **six**
            // characters would not fit a 68x164 balloon vertically, and the
            // same box takes far more set horizontally.
            val vertical = translation.style.sourceStyle.orientation == TextOrientation.VERTICAL &&
                VerticalTextLayout.fits(
                    text = translation.text,
                    boxWidthPx = bounds.width,
                    boxHeightPx = bounds.height,
                    density = context.resources.displayMetrics.density,
                    // The same ceiling the view will use. Asking a different
                    // question here than the view answers is how a balloon
                    // comes to be set vertically and then not fit.
                    glyphSizePx = translation.style.sourceStyle.glyphSizePx,
                )

            // A game's line on a panel plain enough to paint back is erased
            // and written again in place ([ErasedTextView]); any other keeps
            // the box.
            val erased = game && translation.style.sourceStyle.backdrop != null
            // A game's label sits on a button: drawn in a window touches go
            // straight through, so the button works as if nothing covered it —
            // no hand-off, no delay, a hold or a drag reaches it whole. Only
            // possible as a trusted overlay, which alone may be opaque while
            // letting touches through ([TrustedWindows]).
            val passthrough = game && !translation.conversational && trusted?.context != null

            val existing = views[translation.elementId]
            // A new line of a game brings every translation back: the reader
            // looked at the original of the last one, and is reading on.
            // Only a line of dialogue: a name or a label drawn again — the
            // tab sliding back in — is not the reader moving on.
            if (game && translation.conversational && existing == null && gameShowingOriginal) showGameOriginal(false)
            val held = if (existing != null && existing.matches(vertical, ownWindow, game, erased, passthrough)) {
                existing
            } else {
                // A changed writing mode or a changed arrangement needs a new
                // view, not a rebind.
                existing?.let(::remove)
                newHeld(vertical, ownWindow, game, erased, passthrough).also { views[translation.elementId] = it }
            }

            // A fresh render is a fresh balloon: whatever the reader revealed or
            // dismissed belongs to the page that was on screen then. The
            // touchability a dismissal removed comes back with it, because
            // `place` builds the parameters from scratch.
            held.showingOriginal = game && gameShowingOriginal
            held.view.view.alpha = 1f
            held.view.bind(translation)
            if (held.showingOriginal) held.view.showOriginal(true)
            place(held, bounds.left, bounds.top, bounds.width, bounds.height)
            held.view.view.visibility = View.VISIBLE
        }
    }

    fun hide(ids: List<TextElementId>) {
        for (id in ids) {
            views.remove(id)?.let(::remove)
        }
        if (views.values.none { it.game }) gameShowingOriginal = false
    }

    fun clear() {
        views.values.forEach(::remove)
        views.clear()
        gameShowingOriginal = false
    }

    // ------------------------------------------------------------- internals

    /**
     * A translation plus how it is being shown.
     *
     * [showingOriginal] is **view state, not domain state**: a page turn or a
     * re-scan should bring the translation back, so [show] resets it rather
     * than the coordinator remembering it.
     */
    private class Held(
        val view: TranslationView,
        val ownWindow: Boolean,
        val game: Boolean = false,
        val passthrough: Boolean = false,
        /** The window manager that added this view's window, which alone may remove it. */
        val manager: WindowManager? = null,
    ) {
        var showingOriginal = false

        fun matches(vertical: Boolean, ownWindow: Boolean, game: Boolean, erased: Boolean, passthrough: Boolean): Boolean =
            this.ownWindow == ownWindow && this.game == game && (view is VerticalTranslationView) == vertical &&
                (view is ErasedTextView) == erased && this.passthrough == passthrough
    }

    private fun newHeld(
        vertical: Boolean,
        ownWindow: Boolean,
        game: Boolean,
        erased: Boolean = false,
        passthrough: Boolean = false,
    ): Held {
        if (passthrough) {
            val service = requireNotNull(trusted?.context)
            val view: TranslationView = if (erased) ErasedTextView(service) else TranslationTextView(service)
            return Held(view, ownWindow, game, passthrough = true, manager = service.getSystemService(WindowManager::class.java))
        }
        // A game's dialogue is the service's window too, as its labels are:
        // it needs no draw-over-apps permission, which MIUI took back for a
        // minute after an update and every translation was refused, and it
        // is held to no opacity limit while it lets a tap fall through.
        val service = if (game) trusted?.context else null
        val owner = service ?: context
        val view: TranslationView = when {
            erased -> ErasedTextView(owner)
            vertical -> VerticalTranslationView(owner)
            else -> TranslationTextView(owner)
        }
        val held = Held(view, ownWindow, game, manager = service?.getSystemService(WindowManager::class.java))

        if (game) {
            passTapsThrough(held)
        } else if (ownWindow) {
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
                // Stepping aside rather than redrawing what OCR read. What a
                // reader wants from "show me the original" is usually to check
                // the translation against it, and a redraw cannot serve that:
                // it shows the recogniser's reading, so a misread original and
                // its translation agree with each other while both are wrong.
                // Going transparent shows the page itself, whatever read it.
                //
                // Alpha rather than visibility, because an INVISIBLE view stops
                // being a touch target and there would be no way back.
                it.alpha = if (held.showingOriginal) 0f else 1f
            }

            // The other half of what hiding used to cover: getting out of the
            // way. Measured before changing it — the window stays touchable
            // with an invisible child, so the balloon's area became a dead spot
            // that neither answered taps nor let them reach the page. Going
            // away has to mean going away.
            view.view.setOnLongClickListener {
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

        val params = when {
            held.passthrough -> passthroughParams(left, top, w, h)
            held.manager != null -> ownWindowParams(left, top, w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            else -> ownWindowParams(left, top, w, h)
        }
        val manager = held.manager ?: windowManager
        if (held.view.view.isAttachedToWindow) {
            runCatching { manager.updateViewLayout(held.view.view, params) }
        } else {
            runCatching { manager.addView(held.view.view, params) }
        }
    }

    private fun remove(held: Held) {
        if (held.ownWindow) {
            runCatching { (held.manager ?: windowManager).removeView(held.view.view) }
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
            // A trusted container goes with the service that added it; when
            // the service reconnects, a new one is made.
            if (it.isAttachedToWindow || containerManager == null) {
                refreshContainerOrigin(it)
                return true
            }
            it.removeAllViews()
            container = null
            containerManager = null
            views.entries.removeAll { (_, held) -> !held.ownWindow }
        }

        // The accessibility service's own window when it is connected: a
        // trusted overlay is drawn opaque even while letting touches through,
        // so the original no longer shows through at 20% ([TrustedWindows]).
        val serviceContext = trusted?.context
        val owner = serviceContext ?: context
        val manager = serviceContext?.getSystemService(WindowManager::class.java) ?: windowManager
        val type = if (serviceContext != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            if (!canDraw()) return false
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }

        val layout = FrameLayout(owner)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            CONTAINER_FLAGS,
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

        runCatching { manager.addView(layout, params) }.onFailure { return false }
        container = layout
        containerManager = manager
        refreshContainerOrigin(layout)
        return true
    }

    /** A game label's window: trusted, opaque, and letting every touch through. */
    private fun passthroughParams(left: Int, top: Int, width: Int, height: Int) =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            CONTAINER_FLAGS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = left
            y = top
            alpha = 1f
        }

    private fun ownWindowParams(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    ) =
        WindowManager.LayoutParams(
            width,
            height,
            type,
            // Touchable on purpose, and measured — see the note on this class.
            OWN_WINDOW_FLAGS,
            // Translucent so the view can step aside and let the artwork show.
            // Not a weakening of the cover: the view still paints an opaque
            // sampled background, and the window's own alpha stays 1.0 — that
            // is a function of taking touches, not of the pixel format
            // (ADR 008's amendment, and measured again here).
            PixelFormat.TRANSLUCENT,
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

/** How long a press must be held to show the original under a game's translation. */
private const val LONG_PRESS_MS = 450L

/** Time for an untouchable window to reach the input system (spike 3: ~80 ms). */
private const val UNTOUCHABLE_SETTLE_MS = 90L

/** Stay out of the way of the tap just delivered before taking touches again. */
private const val RETOUCHABLE_AFTER_MS = 150L
