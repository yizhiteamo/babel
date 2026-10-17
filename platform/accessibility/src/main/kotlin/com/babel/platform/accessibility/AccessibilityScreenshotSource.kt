package com.babel.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.annotation.RequiresApi
import com.babel.core.common.BabelLogger
import com.babel.platform.screen.ScreenFrameSource
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Takes pictures of the screen through the accessibility service.
 *
 * This replaces MediaProjection, and the reason is entirely about what the user
 * has to put up with: a projection demands fresh consent for every session
 * (Android 15 forbids reusing the token), plants a recording indicator in the
 * status bar, and requires a `mediaProjection` foreground service. The
 * accessibility service is already authorised for V1 and asks for none of that
 * — see ADR 009, including what it costs in privacy visibility.
 *
 * The service instance is set by [BabelAccessibilityService] as it connects,
 * because only the live service can take a screenshot.
 */
@Singleton
class AccessibilityScreenshotSource @Inject constructor(
    private val logger: BabelLogger,
) : ScreenFrameSource, com.babel.platform.screen.TrustedWindows, com.babel.platform.screen.TapForwarder {

    override val canForward: Boolean get() = service != null

    @Volatile
    override var lastTouchAt: Long = 0L
        private set

    override fun tap(x: Float, y: Float, done: () -> Unit) {
        val current = service ?: return done()
        lastTouchAt = SystemClock.elapsedRealtime()
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(
                android.accessibilityservice.GestureDescription.StrokeDescription(
                    android.graphics.Path().apply { moveTo(x, y) },
                    0,
                    TAP_DURATION_MS,
                ),
            )
            .build()
        val sent = current.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = done()
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = done()
            },
            null,
        )
        if (!sent) done()
    }

    override fun drag(points: List<Pair<Float, Float>>, durationMs: Long, done: () -> Unit) {
        val current = service ?: return done()
        if (points.size < 2) return done()
        lastTouchAt = SystemClock.elapsedRealtime()
        val path = android.graphics.Path().apply {
            moveTo(points.first().first, points.first().second)
            points.drop(1).forEach { (x, y) -> lineTo(x, y) }
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, durationMs.coerceIn(MIN_DRAG_MS, MAX_DRAG_MS)))
            .build()
        val sent = current.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription?) = done()
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription?) = done()
            },
            null,
        )
        if (!sent) done()
    }

    /** The connected service, for overlays it can add as trusted windows. */
    override val context: android.content.Context? get() = service

    @Volatile
    private var service: AccessibilityService? = null

    /** Serialises captures so the interval below is actually observed. */
    private val pacing = Mutex()
    private var lastCaptureAt = 0L

    override val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    override val isAvailable: Boolean
        get() = isSupported && service != null

    internal fun attach(service: AccessibilityService) {
        this.service = service
    }

    internal fun detach() {
        watchTouches(false)
        service = null
    }

    /**
     * A one-pixel window that is told of every touch elsewhere on the screen
     * (`FLAG_WATCH_OUTSIDE_TOUCH`), so [lastTouchAt] knows of taps that go
     * straight to the game — a label's translation lets them through, and
     * most of a game's screen has none. Only that a touch happened: for
     * another app's window the platform gives no position.
     *
     * Game mode slows its pictures down on a still screen (`CapturePace`);
     * without this a tap that opened a pop-up left the screen's old
     * translations standing over it for up to two seconds.
     */
    internal fun watchTouches(on: Boolean) {
        main.post {
            val current = service
            val manager = current?.getSystemService(android.view.WindowManager::class.java)
            touchWatcher?.let { watcher -> runCatching { manager?.removeView(watcher) } }
            touchWatcher = null
            if (!on || current == null || manager == null) return@post
            val watcher = android.view.View(current).apply {
                setOnTouchListener { _, event ->
                    if (event.actionMasked == android.view.MotionEvent.ACTION_OUTSIDE) lastTouchAt = SystemClock.elapsedRealtime()
                    false
                }
            }
            val params = android.view.WindowManager.LayoutParams(
                1, 1,
                android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 0
                y = 0
                title = "Babel touch watcher"
            }
            if (runCatching { manager.addView(watcher, params) }.isSuccess) touchWatcher = watcher
        }
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var touchWatcher: android.view.View? = null

    /**
     * Waits out the platform's screenshot interval rather than failing on it.
     *
     * `takeScreenshot` refuses with `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`
     * if asked again too soon, and the caller has no way to tell that apart
     * from a real failure. Measured on a device: scrolling fired scan requests
     * faster than the interval, two of them came back empty, each abandoned its
     * whole scan, and the page then waited on the 1.5s fallback timer — 2.2s of
     * a 3.6s delay before stale translations came down.
     *
     * The interval is the platform's fact, so honouring it belongs here rather
     * than in every caller that might ask twice.
     */
    override suspend fun latestFrame(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val current = service ?: return null

        return pacing.withLock {
            val since = SystemClock.elapsedRealtime() - lastCaptureAt
            if (since < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - since)
            val frame = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { takeScreenshot(current) }
            lastCaptureAt = SystemClock.elapsedRealtime()
            if (frame == null) logger.warn(TAG, "no screenshot within ${CAPTURE_TIMEOUT_MS}ms")
            frame
        }
    }

    override val supportsWindowFrames: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    /**
     * One window alone, paced by the same lock: the platform's interval is
     * shared with whole-display screenshots (measured: ~333ms either way,
     * `docs/milestones/v3.md`, spike 1).
     */
    override suspend fun windowFrame(windowId: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val current = service ?: return null

        // Paced from when the last one was **asked for**, not from when it
        // arrived. The platform's interval runs start to start (spike 1: asked
        // ~300 ms after the last arrived, all succeeded; ~205, half failed).
        // Counting from arrival added the 60-110 ms a capture takes to every
        // frame — measured, game mode read a frame every ~480 ms.
        return pacing.withLock {
            val since = SystemClock.elapsedRealtime() - lastCaptureAt
            if (since < WINDOW_INTERVAL_MS) delay(WINDOW_INTERVAL_MS - since)
            lastCaptureAt = SystemClock.elapsedRealtime()
            val frame = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { takeWindowScreenshot(current, windowId) }
            if (frame == null) lastCaptureAt = SystemClock.elapsedRealtime()
            frame
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun takeWindowScreenshot(current: AccessibilityService, windowId: Int): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            current.takeScreenshotOfWindow(
                windowId,
                Executor(Runnable::run),
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        continuation.resume(result.toSoftwareBitmap())
                    }

                    override fun onFailure(errorCode: Int) {
                        logger.debug(TAG, "window screenshot unavailable, code $errorCode")
                        continuation.resume(null)
                    }
                },
            )
        }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun takeScreenshot(current: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { continuation ->
            val executor = Executor(Runnable::run)

            current.takeScreenshot(
                Display.DEFAULT_DISPLAY,
                executor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        continuation.resume(result.toSoftwareBitmap())
                    }

                    override fun onFailure(errorCode: Int) {
                        // The system throttles screenshots to roughly one every
                        // 333ms. Scans are 1.5s apart so this should not fire,
                        // and if it does the answer is "no frame this time" —
                        // the next scan will get one. Not a session failure.
                        logger.debug(TAG, "screenshot unavailable, code $errorCode")
                        continuation.resume(null)
                    }
                },
            )
        }

    /**
     * The result arrives as a `HardwareBuffer`, which is the wrong shape twice
     * over: a hardware-backed bitmap cannot be read with `getPixels`, and the
     * recognisers reject it. So it is copied into a software bitmap, and the
     * buffer is closed either way — leaving it open leaks a graphics buffer per
     * scan.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun AccessibilityService.ScreenshotResult.toSoftwareBitmap(): Bitmap? {
        val buffer = hardwareBuffer
        return try {
            Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                ?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (failure: Throwable) {
            logger.warn(TAG, "could not read the screenshot", failure)
            null
        } finally {
            buffer.close()
        }
    }

    private companion object {
        const val TAG = "Screenshot"

        /**
         * As long as a finger's tap. Spike 3 measured a 10 ms stroke landing
         * ~20 ms sooner than 40, but Blue Archive's buttons outside the story
         * do not take one that short: a tap handed on from a chat list's
         * entry opened nothing, and adb's own ~50 ms taps did not either,
         * where 150 ms did.
         */
        const val TAP_DURATION_MS = 120L

        /** A handed-on drag keeps the finger's own pace, within these bounds. */
        const val MIN_DRAG_MS = 80L
        const val MAX_DRAG_MS = 2000L

        /**
         * The platform allows roughly one screenshot every 333ms. Asking at a
         * slightly longer interval leaves room for the clock disagreeing with
         * itself, and costs nothing: nothing here wants screenshots faster.
         */
        const val MIN_INTERVAL_MS = 400L

        /** Start to start, a little over the platform's ~333 ms (`windowFrame`). */
        const val WINDOW_INTERVAL_MS = 350L

        /**
         * How long to wait for the platform to answer before giving up on a
         * frame.
         *
         * `takeScreenshot` promises a callback and does not promise to make
         * one: neither `onSuccess` nor `onFailure` is guaranteed to arrive.
         * Waiting for it without a bound is not just a slow scan — this runs
         * under [pacing], and the caller holds a second lock across the whole
         * scan, so one call that never answers stops manga mode for the life of
         * the process. Every tick afterwards finds the lock held and returns
         * silently; only a restart clears it, which is exactly the shape of a
         * failure seen on an emulator and not reproduced since
         * (`docs/milestones/v2.md`).
         *
         * Timing out is a state the caller already handles: it is the same "no
         * frame this time" that `onFailure` produces, and the next scan tries
         * again. Screenshots answer in the low hundreds of milliseconds, so
         * five seconds is far outside the working range and far inside
         * forever.
         */
        const val CAPTURE_TIMEOUT_MS = 5_000L
    }
}
