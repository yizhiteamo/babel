package com.babel.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.core.model.Revision
import com.babel.domain.render.TranslationRenderer
import com.babel.domain.translation.TranslationCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    lateinit var coordinator: TranslationCoordinator

    @Inject
    lateinit var renderer: TranslationRenderer

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var logger: BabelLogger

    private var scope: CoroutineScope? = null

    /**
     * Conflated: content-change events arrive far faster than a tree scan can
     * run, and only the latest matters. Combined with the delay in
     * [runScanLoop] this debounces a burst of scroll events into one scan.
     */
    private val scanRequests = Channel<Unit>(Channel.CONFLATED)

    private var generation = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        logger.info(TAG, "accessibility service connected")

        val newScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        scope = newScope

        newScope.launch { textSource.events().collect(coordinator::submit) }
        newScope.launch { coordinator.renderUpdates.collect(renderer::apply) }
        newScope.launch { runScanLoop() }

        coordinator.start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // A different window or app: everything previously on screen is
                // gone, so drop it before the next scan rather than letting
                // overlays linger over unrelated content.
                scope?.launch { textSource.clear() }
                scanRequests.trySend(Unit)
            }

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
        coordinator.stop()
        scope?.cancel()
        scope = null
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
        } ?: return

        val rawTexts = extractor.extract(root)
        if (rawTexts.isEmpty()) return

        generation += 1
        val elements = TextElementFactory.create(
            rawTexts = rawTexts,
            windowId = root.windowId,
            packageName = root.packageName?.toString(),
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
    }
}
