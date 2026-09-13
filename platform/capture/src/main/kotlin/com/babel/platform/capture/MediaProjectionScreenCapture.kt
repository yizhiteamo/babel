package com.babel.platform.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import com.babel.core.common.BabelLogger
import com.babel.domain.vision.CaptureState
import com.babel.domain.vision.ScreenCaptureController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reads frames off the screen through MediaProjection.
 *
 * Frames stay inside this module as [Bitmap]s. The domain never sees pixels —
 * it receives recognised text — which keeps image handling a platform concern
 * and means the pipeline does not change shape if capture is replaced.
 *
 * Nothing is written to disk. `docs/systems/privacy.md` asks that raw screen
 * content not be persisted, and a captured comic page is exactly that.
 */
@Singleton
class MediaProjectionScreenCapture @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: BabelLogger,
) : ScreenCaptureController {

    private val _state = MutableStateFlow(CaptureState.IDLE)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val handler = Handler(Looper.getMainLooper())

    private val frameLock = Any()

    /**
     * The most recent frame, kept because a virtual display only produces one
     * when the screen changes. A comic page is static by nature, so
     * `acquireLatestImage` returns null almost always — reading on demand would
     * work on video and fail on exactly the content V2 targets.
     */
    private var latest: Bitmap? = null

    /**
     * Ends the session when the system or the user revokes it, so state does
     * not claim a session that has already gone.
     */
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            logger.info(TAG, "projection stopped by the system")
            releaseCapture()
            _state.value = CaptureState.IDLE
        }
    }

    fun markRequesting() {
        _state.value = CaptureState.REQUESTING
    }

    /**
     * Starts a session from the consent the system returned.
     *
     * Must be called only once the service is already in the foreground —
     * `getMediaProjection` throws otherwise.
     *
     * @return false when the session could not be started.
     */
    fun begin(resultCode: Int, consent: Intent): Boolean {
        releaseCapture()

        val manager = context.getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            logger.warn(TAG, "no MediaProjectionManager on this device")
            _state.value = CaptureState.FAILED
            return false
        }

        return runCatching {
            val started = manager.getMediaProjection(resultCode, consent)
                ?: error("consent did not yield a projection")
            started.registerCallback(projectionCallback, handler)
            projection = started

            val metrics = displayMetrics()
            openVirtualDisplay(started, metrics)

            _state.value = CaptureState.ACTIVE
            logger.info(TAG, "capture session active at ${metrics.widthPixels}x${metrics.heightPixels}")
            true
        }.getOrElse { failure ->
            logger.error(TAG, "could not start capture", failure)
            releaseCapture()
            _state.value = CaptureState.FAILED
            false
        }
    }

    /**
     * A copy of the most recent frame, or null before the first one arrives.
     *
     * A copy rather than the cached bitmap itself: recognition runs
     * asynchronously and may take hundreds of milliseconds, during which a new
     * frame could recycle the one being read. The caller owns the copy and
     * should recycle it.
     */
    fun latestFrame(): Bitmap? = synchronized(frameLock) {
        val current = latest ?: return null
        current.copy(current.config ?: Bitmap.Config.ARGB_8888, false)
    }

    /** Keeps only the newest frame; holding more would just retain screen content. */
    private fun onFrameAvailable(reader: ImageReader) {
        val image = reader.acquireLatestImage() ?: return
        val bitmap = image.use(::toBitmap) ?: return

        val previous = synchronized(frameLock) {
            val old = latest
            latest = bitmap
            old
        }
        previous?.recycle()
    }

    /**
     * Copies out of the reader's buffer, honouring row stride.
     *
     * The buffer is padded to a stride that is usually wider than the display,
     * so a straight copy produces a skewed image; the extra columns are cropped
     * after the copy.
     */
    private fun toBitmap(image: Image): Bitmap? = runCatching {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        padded.copyPixelsFromBuffer(plane.buffer)

        if (rowPadding == 0) {
            padded
        } else {
            Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                .also { if (it !== padded) padded.recycle() }
        }
    }.getOrElse { failure ->
        logger.warn(TAG, "could not read a frame", failure)
        null
    }

    override fun stop() {
        releaseCapture()
        _state.value = CaptureState.IDLE
    }

    private fun openVirtualDisplay(projection: MediaProjection, metrics: DisplayMetrics) {
        // maxImages = 2: one frame being read while the next arrives. More
        // would only hold additional screen content in memory for no gain.
        val reader = ImageReader.newInstance(
            metrics.widthPixels,
            metrics.heightPixels,
            PixelFormat.RGBA_8888,
            2,
        )
        imageReader = reader

        reader.setOnImageAvailableListener(::onFrameAvailable, handler)

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            handler,
        )
    }

    private fun displayMetrics(): DisplayMetrics {
        val windowManager = context.getSystemService(WindowManager::class.java)
        val metrics = DisplayMetrics()

        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getRealMetrics(metrics)

        if (metrics.widthPixels == 0 || metrics.heightPixels == 0) {
            val fallback = context.resources.displayMetrics
            metrics.setTo(fallback)
        }
        return metrics
    }

    private fun releaseCapture() {
        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.setOnImageAvailableListener(null, null)
        imageReader?.close()
        imageReader = null

        synchronized(frameLock) {
            latest?.recycle()
            latest = null
        }

        projection?.let {
            runCatching { it.unregisterCallback(projectionCallback) }
            it.stop()
        }
        projection = null
    }

    private companion object {
        const val TAG = "ScreenCapture"
        const val VIRTUAL_DISPLAY_NAME = "babel-capture"
    }
}

/** Closes the image once [block] has copied what it needs out of it. */
private inline fun <T> Image.use(block: (Image) -> T): T = try {
    block(this)
} finally {
    close()
}
