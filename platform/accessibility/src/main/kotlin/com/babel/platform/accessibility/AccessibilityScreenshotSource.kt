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
) : ScreenFrameSource {

    @Volatile
    private var service: AccessibilityService? = null

    /** Serialises captures so the interval below is actually observed. */
    private val pacing = Mutex()
    private var lastCaptureAt = 0L

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service != null

    internal fun attach(service: AccessibilityService) {
        this.service = service
    }

    internal fun detach() {
        service = null
    }

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
            takeScreenshot(current).also { lastCaptureAt = SystemClock.elapsedRealtime() }
        }
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
         * The platform allows roughly one screenshot every 333ms. Asking at a
         * slightly longer interval leaves room for the clock disagreeing with
         * itself, and costs nothing: nothing here wants screenshots faster.
         */
        const val MIN_INTERVAL_MS = 400L
    }
}
