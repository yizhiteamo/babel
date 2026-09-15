package com.babel.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import com.babel.core.model.CoordinateSpace
import com.babel.core.model.Revision
import com.babel.core.model.TextBounds
import com.babel.domain.render.RenderUpdate
import com.babel.domain.render.TranslationRenderer
import com.babel.domain.scope.TranslationScopePolicy
import com.babel.domain.translation.TranslationCoordinator
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ImageTextScanner
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * Hosts the pipeline for as long as the user has the service enabled.
 *
 * The service is instantiated by the system, so dependencies arrive by field
 * injection rather than a constructor.
 *
 * It wires acquisition to the coordinator and the coordinator to the renderer,
 * but only through `:domain` interfaces — it cannot see `:data:translation` or
 * `:platform:overlay`, so it cannot reach a provider or a window directly.
 * Lifecycle has to live here because nothing else survives exactly as long as
 * the service does.
 */
@AndroidEntryPoint
class BabelAccessibilityService : AccessibilityService() {

    @Inject
    lateinit var textSource: AccessibilityTextSource

    @Inject
    lateinit var extractor: NodeTextExtractor

    @Inject
    lateinit var scopePolicy: TranslationScopePolicy

    @Inject
    lateinit var mangaMode: MangaModeController

    @Inject
    lateinit var screenshots: AccessibilityScreenshotSource

    @Inject
    lateinit var imageScanner: ImageTextScanner

    @Inject
    lateinit var indicator: MangaModeIndicator

    @Inject
    lateinit var coordinator: TranslationCoordinator

    @Inject
    lateinit var renderer: TranslationRenderer

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var logger: BabelLogger

    private var scope: CoroutineScope? = null

    /**
     * Teardown has to outlive [scope]. Cancelling the pipeline also kills the
     * coroutine collecting render updates, so the coordinator's final ClearAll
     * would never reach the renderer and the overlay would stay on screen after
     * the user switched the service off.
     *
     * `Main.immediate` so it completes synchronously when teardown is already
     * running on the main thread, which is where the service is destroyed.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Conflated: content-change events arrive far faster than a tree scan can
     * run, and only the latest matters. Combined with the delay in
     * [runScanLoop] this debounces a burst of scroll events into one scan.
     */
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * The same signal for the image path, kept separate so one path falling
     * behind cannot swallow the other's wake-ups.
     *
     * The image path used to be driven only by a 1.5s timer, on the reasoning
     * that a comic page fires no content-change event. Measured on a device,
     * that is false for the case that matters: turning to a comic in a browser
     * fires one immediately, and the image path sat waiting for its timer
     * anyway — 3.0s of a 5.9s wait was this. The timer stays as a fallback for
     * apps that really are silent.
     */
    private val imageScanRequests = Channel<Unit>(Channel.CONFLATED)

    /**
     * Scrolling, reported separately because it means something different.
     *
     * The image path cannot follow a scroll. Its coordinates come from pixels,
     * and the pixels have moved — so every translation it has drawn is now over
     * content it does not describe. The node path has no such problem: it reads
     * fresh bounds from the tree on the same event.
     *
     * Kept off [imageScanRequests] because that one goes through the scan lock,
     * and taking overlays down must not wait behind a scan that is already
     * running — the scan in question is reading the screen the user just left.
     */
    private val imageScrollSignals = Channel<Unit>(Channel.CONFLATED)

    private var generation = 0L

    /** Identity of the window the last scan read, to detect a real app change. */
    private var lastWindowKey: String? = null

    /**
     * The package the image path is currently translating, or null when it is
     * not running at all.
     *
     * One field rather than a flag per reason, because every reason to stop has
     * the same consequence — what was recognised on the old screen has to go.
     * It covers leaving scope, the node path taking the screen back, manga mode
     * being switched off, and the case that had no handling whatsoever: walking
     * into a different app while manga mode stays on. Reported from a device as
     * bubbles left behind on the next thing the user opened.
     *
     * Volatile because [followMangaMode] writes it too, so that switching the
     * mode off clears immediately rather than at the next tick. A lost update
     * there can only cost one redundant clear.
     */
    @Volatile
    private var imagePathOwner: String? = null

    /** Held for the length of a scan, so the two drivers cannot overlap. */
    private val imageScanning = Mutex()

    override fun onServiceConnected() {
        super.onServiceConnected()
        logger.info(TAG, "accessibility service connected")

        // Only a live service can take a screenshot, so manga mode's frames
        // come from here (ADR 009).
        screenshots.attach(this)
        mangaMode.onServiceAvailabilityChanged()

        val newScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        scope = newScope

        newScope.launch { textSource.events().collect(coordinator::submit) }
        newScope.launch { imageScanner.events().collect(coordinator::submit) }
        newScope.launch { coordinator.renderUpdates.collect(renderer::apply) }
        newScope.launch { runScanLoop() }
        newScope.launch { runImageScanLoop() }
        newScope.launch { runImageEventLoop() }
        newScope.launch { runImageScrollLoop() }
        newScope.launch { followMangaMode() }

        coordinator.start()
    }

    /**
     * The fallback driver: a fixed interval, for apps that report no content
     * changes at all.
     *
     * Cheap when nothing is happening — [ImageTextScanner] compares each frame
     * against the last it recognised and returns immediately when the page has
     * not changed, so a static comic costs one recognition, not one per tick.
     * It is not cheap enough to shorten, though: every tick takes a full screen
     * capture, so a faster timer would pay that forever. Speed comes from
     * [runImageEventLoop] instead, which fires only when something moved.
     */
    private suspend fun runImageScanLoop() {
        val current = scope ?: return
        while (current.isActive) {
            delay(IMAGE_SCAN_INTERVAL_MS)
            considerImageScan()
        }
    }

    /**
     * The fast driver: the same content-change events the node path runs on.
     *
     * Measured on a device before this existed: turning to a comic in a browser
     * fires a content-change event straight away, the node path acted on it
     * within 250ms, and the image path waited for its own timer regardless —
     * 3.0s of a 5.9s wait was that. Debounced exactly as [runScanLoop] is, so a
     * burst of scroll events is one scan.
     */
    private suspend fun runImageEventLoop() {
        for (request in imageScanRequests) {
            delay(SCAN_DEBOUNCE_MS)
            considerImageScan()
        }
    }

    /**
     * Takes image translations down as soon as the page moves under them.
     *
     * Measured on a device before this existed: a scroll left three
     * translations sitting at their old screen positions for **3.6 seconds**,
     * over artwork they had nothing to do with, while the untranslated bubbles
     * that had scrolled into view sat beside them untouched.
     *
     * Nothing here restores them — [ImageTextScanner.clear] resets the frame it
     * last recognised, so the next settled frame is rescanned and they come back
     * where they belong. The translations themselves are cached, so coming back
     * costs no provider call: that matters more now that a provider can be
     * something the user pays per request (ADR 010).
     */
    private suspend fun runImageScrollLoop() {
        for (signal in imageScrollSignals) {
            imageScanner.clear()
        }
    }

    /**
     * Decides whether to read the screen as an image, and does it. Both drivers
     * share this: there is one rule, written once.
     *
     * Both acquisition paths feed the one coordinator, so nothing downstream
     * knows which of them produced an element.
     */
    private suspend fun considerImageScan() {
        // Two drivers, one scanner. A scan takes seconds and holds a full-screen
        // bitmap, so a second one must not start alongside it. Skipping rather
        // than queueing is deliberate: whatever provoked this will still be on
        // screen when the running scan or the next tick looks.
        if (!imageScanning.tryLock()) return
        try {
            if (mangaMode.state.value != CaptureState.ACTIVE) {
                standDownImagePath()
                return
            }

            // Scope is checked here too, and it has to be.
            //
            // The node path checks it in [scanVisibleText], but the image path
            // takes over screens that path cannot read — so without this,
            // turning manga mode on and walking into a banking app would read
            // the whole screen with no policy applied anywhere. A capture reads
            // everything on display, which makes scope matter more here than it
            // does for nodes, not less (`docs/systems/scope.md`).
            val front = activePackage()
            if (!scopePolicy.isInScope(front)) {
                standDownImagePath()
                return
            }

            val layout = readScreenLayout()
            if (!layout.imagePathOwnsScreen) {
                // The node path can read this screen, so it should: it is
                // faster and more accurate than recognising pixels, and running
                // both would stack two layers of overlays.
                standDownImagePath()
                return
            }

            // A different app, with manga mode still on. Nothing else notices:
            // the change detector compares pixels, and one white page followed
            // by another does not clear the threshold.
            if (imagePathOwner != front) {
                if (imagePathOwner != null) imageScanner.clear()
                imagePathOwner = front
            }

            imageScanner.scanOnce(front, layout.interfaceAreas, layout.contentArea)
        } finally {
            imageScanning.unlock()
        }
    }

    /**
     * Takes the image path's translations off the screen and stops it running.
     *
     * Idempotent, so the loop can call it on every tick that the image path
     * should not be running without clearing over and over.
     */
    private suspend fun standDownImagePath() {
        if (imagePathOwner == null) return
        imagePathOwner = null
        imageScanner.clear()
    }

    /**
     * What one walk of the window trees says about the screen in front.
     *
     * Three questions are asked of the same information, so it is read once:
     * which areas the image path must leave alone, which area is the app's
     * content, and — the one that decides which path runs at all — whether the
     * text path can read this screen already.
     */
    private class ScreenLayout(
        /**
         * Parts of the screen image translation must leave alone.
         *
         * Manga mode reads the whole display as pixels, so it sees the app's
         * chrome, the address bar and the status bar clock alongside the
         * artwork, and translates all of it. Reported from a device: a comic
         * page covered in translations of browser tab titles.
         *
         * The rule is a definition rather than a guess about screen positions:
         * image translation exists to read what the text path **cannot**, so
         * anything the text path can already see is interface, not art. The
         * accessibility tree is exactly that list, and it comes with bounds.
         * The system bars are added separately — they belong to SystemUI's
         * window, not to the app's tree.
         */
        val interfaceAreas: List<TextBounds>,
        /**
         * The app's content area, when the tree makes it obvious.
         *
         * Excluding what accessibility can see only reaches as far as what it
         * exposes, and measured on this device Chromium exposes **two** text
         * nodes for its entire window — its tab titles are not among them. No
         * exclusion rule can remove what it cannot see.
         *
         * The same tree does expose the content area, as the container node
         * that [isLabelSized] rejects: 1083x1383 offset below the toolbar,
         * exactly the page. Turning the question around and keeping only what
         * falls *inside* that container removes every kind of chrome at once —
         * tab strip, address bar, system bars — without naming any of them.
         *
         * Null when no such container is found, in which case nothing is
         * restricted: a wrong guess here would silence the whole screen.
         */
        val contentArea: TextBounds?,
        /** Label-sized text nodes sitting inside [contentArea]. */
        val labelsInContent: Int,
    ) {
        /**
         * Whether this screen holds text **only** the image path can reach.
         *
         * The same rule that decides which areas to skip, applied to the screen
         * as a whole: image translation exists to read what the text path
         * cannot. A comic page is one image, so the tree exposes no labels
         * inside it and the image path takes over; an article exposes a label
         * per paragraph, so the text path keeps it.
         *
         * This is what stops manga mode from being a global takeover. Leaving
         * it on and opening an article used to put the whole article through
         * OCR — slower, less accurate, and covered in opaque boxes — while the
         * path that could read it properly sat suspended.
         */
        val imagePathOwnsScreen: Boolean
            get() = labelsInContent < MIN_CONTENT_LABELS
    }

    private fun readScreenLayout(): ScreenLayout {
        val screen = resources.displayMetrics.let { it.widthPixels.toLong() * it.heightPixels }
        val bounds = interfaceRoots().flatMap { extractor.extract(it) }.map { it.bounds }
        val (labels, containers) = bounds.partition { it.isLabelSized(screen) }

        val content = containers.maxByOrNull { it.width.toLong() * it.height }
        val inContent = when (content) {
            // No container to judge by, so every label counts: whatever the
            // tree exposed is text the node path can read.
            null -> labels.size
            else -> labels.count { it.centreIsIn(content) }
        }

        logger.debug(TAG, "screen layout: $inContent labels inside the content area")
        return ScreenLayout(labels + systemBars(), content, inContent)
    }

    /**
     * Every window's tree, not just the front one.
     *
     * A browser's tab strip lives in a different window from its page, so the
     * active window alone misses exactly the chrome that provoked this —
     * measured: the address bar was excluded and the tab titles were not.
     *
     * Our own windows are skipped. The translations we draw carry text of their
     * own, and feeding them back in as areas to avoid would have the overlay
     * suppress the next scan of the very region it occupies.
     */
    private fun interfaceRoots(): List<AccessibilityNodeInfo> = try {
        val fromWindows = windows.orEmpty()
            .mapNotNull { it.root }
            .filterNot { it.packageName?.toString() == packageName }
        fromWindows.ifEmpty { listOfNotNull(rootInActiveWindow) }
    } catch (failure: Exception) {
        logger.warn(TAG, "could not read the window list", failure)
        emptyList()
    }

    /**
     * Whether this is small enough to be an interface label rather than a
     * container.
     *
     * Measured on a comic page: the browser reports a text node of 1083x1383 —
     * the whole content area — and treating it as interface dropped every
     * bubble on the page. A node covering a quarter of the display is not
     * telling us where text sits, it is a box with text somewhere inside it.
     * V1 met the same container the first time it sized type from bounds.
     */
    private fun TextBounds.isLabelSized(screenArea: Long): Boolean =
        width.toLong() * height <= screenArea / LABEL_MAX_SCREEN_FRACTION

    /**
     * Status and navigation bars, which no app's tree contains.
     *
     * The metrics call is API 30, which manga mode already requires — but the
     * requirement lives in [MangaModeController], so it is restated here rather
     * than assumed across a module boundary.
     */
    private fun systemBars(): List<TextBounds> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()

        val metrics = getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics ?: return emptyList()
        val insets = metrics.windowInsets
            .getInsets(WindowInsets.Type.systemBars())
        val width = metrics.bounds.width()
        val height = metrics.bounds.height()

        return buildList {
            if (insets.top > 0) {
                add(TextBounds(0, 0, width, insets.top, CoordinateSpace.SCREEN))
            }
            if (insets.bottom > 0) {
                add(TextBounds(0, height - insets.bottom, width, height, CoordinateSpace.SCREEN))
            }
        }
    }

    /** Package of whatever is in front, or null when it cannot be read. */
    private fun activePackage(): String? = try {
        rootInActiveWindow?.packageName?.toString()
    } catch (failure: Exception) {
        logger.warn(TAG, "could not read the active window", failure)
        null
    }

    /**
     * Screen reading used to be visible because MediaProjection forced a
     * notification, and `docs/systems/privacy.md` welcomed it rather than
     * merely tolerating it. Nothing forces one now, so the notification is
     * posted deliberately: the platform stopped requiring visibility, the
     * project still does (ADR 009).
     */
    private suspend fun followMangaMode() {
        var wasActive = false
        mangaMode.state.collect { state ->
            val active = state == CaptureState.ACTIVE
            if (active == wasActive) return@collect
            wasActive = active

            if (active) {
                indicator.show()
            } else {
                indicator.hide()
                // Immediately rather than at the next tick of the scan loop:
                // the user switched the mode off and expects the bubbles gone.
                standDownImagePath()
                // And let go of the models. Switched off is the one moment we
                // know image translation is finished with for now; anything
                // shorter — a scroll, an app switch — comes back too soon to be
                // worth a reload (`docs/milestones/v2.md`).
                imageScanner.release()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            -> {
                scanRequests.trySend(Unit)
                imageScanRequests.trySend(Unit)
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                scanRequests.trySend(Unit)
                imageScanRequests.trySend(Unit)
                // Cheap enough for the main thread: one volatile read, and the
                // work itself happens on [runImageScrollLoop].
                if (imagePathOwner != null) imageScrollSignals.trySend(Unit)
            }

            else -> Unit
        }
    }

    override fun onInterrupt() {
        logger.info(TAG, "accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        lastWindowKey = null
        imagePathOwner = null
        screenshots.detach()
        mangaMode.onServiceAvailabilityChanged()
        indicator.hide()
        coordinator.stop()
        scope?.cancel()
        scope = null
        teardownScope.launch { renderer.apply(RenderUpdate.ClearAll) }
        logger.info(TAG, "accessibility service torn down")
    }

    private suspend fun runScanLoop() {
        for (request in scanRequests) {
            delay(SCAN_DEBOUNCE_MS)
            scanVisibleText()
        }
    }

    private suspend fun scanVisibleText() {
        val root = try {
            rootInActiveWindow
        } catch (failure: Exception) {
            logger.warn(TAG, "could not read the active window", failure)
            null
        } ?: run {
            logger.debug(TAG, "no active window to scan")
            return
        }

        // Manga mode owns a screen only while it is the one that can read it.
        // Both paths feed the same coordinator, so running both would translate
        // the same screen twice and stack two layers of overlays — but standing
        // down on *every* screen is what made manga mode ruin plain text. The
        // decision is read live rather than shared between the two coroutines,
        // so there is no flag to race over.
        if (mangaMode.state.value == CaptureState.ACTIVE) {
            if (readScreenLayout().imagePathOwnsScreen) {
                if (lastWindowKey != null) {
                    lastWindowKey = null
                    textSource.clear()
                }
                return
            }

            // This screen is text, and the image path may have been part-way
            // through recognising the previous one when the user moved. Taking
            // its overlays down here, on the window event, rather than leaving
            // it to notice at the end of its own scan is the difference between
            // a flicker and four seconds of opaque boxes sitting on the
            // article — measured on a device, which is where this was reported.
            standDownImagePath()
        }

        val packageName = root.packageName?.toString()

        // Checked before walking the tree, so an out-of-scope app costs nothing
        // rather than being filtered element by element afterwards
        // (`docs/systems/scope.md`).
        if (!scopePolicy.isInScope(packageName)) {
            // Leaving a translated app has to take its overlays with it,
            // otherwise the previous app's translations sit over the launcher.
            if (lastWindowKey != null) {
                lastWindowKey = null
                textSource.clear()
            }
            return
        }

        // Clear only when the window actually changed. TYPE_WINDOW_STATE_CHANGED
        // fires within a single app too — panel updates, dialogs, lazily loaded
        // content — and clearing on every one of those tears the overlay down
        // and re-translates the same screen in a loop. Keying off what was
        // actually scanned makes "different window" mean what it says.
        val windowKey = "$packageName:${root.windowId}"
        if (windowKey != lastWindowKey) {
            lastWindowKey = windowKey
            textSource.clear()
        }

        val rawTexts = extractor.extract(root)
        logger.debug(TAG, "scanned $windowKey: ${rawTexts.size} text nodes")
        if (rawTexts.isEmpty()) return

        generation += 1
        val elements = TextElementFactory.create(
            rawTexts = rawTexts,
            windowId = root.windowId,
            packageName = packageName,
            revision = Revision(generation),
        )
        textSource.publish(elements)
    }

    private companion object {
        const val TAG = "AccessibilityService"

        /**
         * Long enough that a flick-scroll produces one scan instead of dozens,
         * short enough that text appears without feeling delayed.
         */
        const val SCAN_DEBOUNCE_MS = 250L

        /**
         * Manga mode has no event to react to — a comic page does not fire
         * content-changed — so it polls. The change check inside the scanner is
         * what keeps that from being expensive.
         */
        const val IMAGE_SCAN_INTERVAL_MS = 1_500L

        /** A text node bigger than a quarter of the display is a container. */
        const val LABEL_MAX_SCREEN_FRACTION = 4

        /**
         * How many labels inside the content area mean the text path can read
         * this screen, so the image path should leave it alone.
         *
         * Set from measurement, like [LABEL_MAX_SCREEN_FRACTION] and
         * `FrameChangeDetector.CHANGED_FRACTION` before it. Measured in
         * Chromium on this device: a comic page reports **0** labels inside its
         * content area on every sample, an article reports **8**. The threshold
         * sits in that gap rather than on either edge, because a reader may
         * label its own content area with a page number or a chapter title
         * without that making the page readable as text.
         */
        const val MIN_CONTENT_LABELS = 3
    }
}
