package com.babel.platform.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.domain.render.RenderUpdate
import com.babel.domain.render.TranslationRenderer
import com.babel.domain.translation.TranslationCoordinator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Holds the MediaProjection session.
 *
 * A foreground service is not a design choice: since Android 10 a projection
 * cannot run without one, and since Android 14 the service must declare
 * `mediaProjection` as its type and hold
 * `FOREGROUND_SERVICE_MEDIA_PROJECTION`.
 *
 * The obligatory notification is welcome rather than merely tolerated — it is
 * what makes screen reading visible to the user for as long as it lasts, which
 * `docs/systems/privacy.md` asks of anything touching screen content.
 *
 * The service must be foreground **before** `getMediaProjection` is called;
 * doing it afterwards throws.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject
    lateinit var capture: MediaProjectionScreenCapture

    @Inject
    lateinit var textSource: CaptureTextSource

    @Inject
    lateinit var coordinator: TranslationCoordinator

    @Inject
    lateinit var renderer: TranslationRenderer

    @Inject
    lateinit var dispatchers: DispatcherProvider

    @Inject
    lateinit var logger: BabelLogger

    private val handler = Handler(Looper.getMainLooper())

    private var scope: CoroutineScope? = null

    /**
     * Outlives [scope] so the final ClearAll still reaches the renderer:
     * cancelling the pipeline also kills the coroutine collecting render
     * updates, and translations would otherwise stay on screen after the
     * session ends. Same reasoning as the accessibility service.
     */
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_STOP -> stopSelf()
            else -> {
                logger.warn(TAG, "started without an action; stopping")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        goToForeground()

        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val consent: Intent? = intent.extractConsent()
        if (consent == null) {
            logger.warn(TAG, "no consent data in start intent; stopping")
            stopSelf()
            return
        }

        val started = capture.begin(resultCode, consent)
        if (!started) {
            logger.warn(TAG, "could not begin a capture session; stopping")
            stopSelf()
            return
        }

        handler.postDelayed(::reportFirstFrame, FIRST_FRAME_DELAY_MS)
        beginPipeline()
    }

    /**
     * Feeds recognised text into the same coordinator the accessibility path
     * uses. Nothing downstream knows which source produced an element.
     */
    private fun beginPipeline() {
        val newScope = CoroutineScope(SupervisorJob() + dispatchers.default)
        scope = newScope

        newScope.launch { textSource.events().collect(coordinator::submit) }
        newScope.launch { coordinator.renderUpdates.collect(renderer::apply) }
        newScope.launch { runScanLoop() }

        coordinator.start()
    }

    /**
     * Polls at a fixed interval.
     *
     * A placeholder, not the design: "Page/region change detection" is its own
     * milestone item, and re-recognising an unchanged page is wasted work and
     * battery. Polling exists so the pipeline can run end to end before that
     * lands.
     */
    private suspend fun runScanLoop() {
        val current = scope ?: return
        while (current.isActive) {
            delay(SCAN_INTERVAL_MS)
            textSource.scanOnce()
        }
    }

    /**
     * Confirms frames actually arrive, rather than only that the session was
     * created — a virtual display can exist while its reader never fills.
     *
     * Logs dimensions only, never content: a captured frame is screen content
     * and `docs/systems/privacy.md` keeps that out of diagnostics.
     */
    private fun reportFirstFrame() {
        val frame = capture.latestFrame()
        if (frame == null) {
            logger.warn(TAG, "session is active but no frame arrived")
            return
        }
        logger.info(TAG, "first frame ${frame.width}x${frame.height}")
        frame.recycle()
    }

    @Suppress("DEPRECATION")
    private fun Intent.extractConsent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_CONSENT, Intent::class.java)
        } else {
            getParcelableExtra(EXTRA_CONSENT)
        }

    private fun goToForeground() {
        createChannel()
        val notification = buildNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.capture_channel_name),
            // Low: the notification must be present and readable, but it is a
            // status indicator, not something to interrupt reading with.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.capture_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        coordinator.stop()
        scope?.cancel()
        scope = null
        // Outside the cancelled scope, or the renderer never hears it and
        // translations stay on screen after the session ends.
        teardownScope.launch { renderer.apply(RenderUpdate.ClearAll) }
        capture.stop()
        logger.info(TAG, "capture service destroyed")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "babel.capture"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.babel.capture.START"
        private const val ACTION_STOP = "com.babel.capture.STOP"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_CONSENT = "consent"

        /** Long enough for the virtual display to produce its first frame. */
        private const val FIRST_FRAME_DELAY_MS = 800L

        /** Placeholder cadence until change detection replaces polling. */
        private const val SCAN_INTERVAL_MS = 1_500L

        /**
         * @param consent the Intent returned by the system consent dialog. It
         *   authorises exactly one session and cannot be stored and reused.
         */
        fun startIntent(context: Context, resultCode: Int, consent: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_CONSENT, consent)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenCaptureService::class.java).apply { action = ACTION_STOP }
    }
}
