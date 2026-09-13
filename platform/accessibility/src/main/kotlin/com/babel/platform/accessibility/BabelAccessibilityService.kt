package com.babel.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.model.Revision
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

    private var generation = 0L

    /** Identity of the window the last scan read, to detect a real app change. */
    private var lastWindowKey: String? = null

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
        newScope.launch { followMangaMode() }

        coordinator.start()
    }

    /**
     * Reads the screen as an image while manga mode is on. Both acquisition
     * paths feed the one coordinator, so nothing downstream knows which of them
     * produced an element.
     *
     * A fixed interval, but a cheap one: [ImageTextScanner] compares each frame
     * against the last it recognised and returns immediately when the page has
     * not changed, so a static comic costs one recognition, not one per tick.
     */
    private suspend fun runImageScanLoop() {
        val current = scope ?: return
        while (current.isActive) {
            delay(IMAGE_SCAN_INTERVAL_MS)
            if (mangaMode.state.value == CaptureState.ACTIVE) imageScanner.scanOnce()
        }
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
                imageScanner.clear()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            -> scanRequests.trySend(Unit)

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

        // Manga mode owns the screen while it runs. Both paths feed the same
        // coordinator, so leaving this one active would translate the same
        // screen twice and stack two layers of overlays on top of each other.
        if (mangaMode.state.value == CaptureState.ACTIVE) {
            if (lastWindowKey != null) {
                lastWindowKey = null
                textSource.clear()
            }
            return
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
    }
}
