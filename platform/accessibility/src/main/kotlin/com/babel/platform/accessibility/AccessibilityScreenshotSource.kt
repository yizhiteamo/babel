package com.babel.platform.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import com.babel.core.common.BabelLogger
import com.babel.platform.screen.ScreenFrameSource
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

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

    override val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service != null

    internal fun attach(service: AccessibilityService) {
        this.service = service
    }

    internal fun detach() {
        service = null
    }

    override suspend fun latestFrame(): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return takeScreenshot(service ?: return null)
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
    }
}
