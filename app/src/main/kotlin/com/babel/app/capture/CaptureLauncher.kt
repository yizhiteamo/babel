package com.babel.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.babel.platform.capture.MediaProjectionScreenCapture
import com.babel.platform.capture.ScreenCaptureService

/**
 * Starts and stops a screen capture session.
 *
 * Consent arrives as an Activity result, so a session can only be initiated
 * from the UI layer — which is why this lives here and not in the capture
 * module, which has no Activity.
 *
 * From Android 15 the user must consent afresh for every session and the
 * returned token cannot be stored, so there is nothing worth caching: each
 * start goes through the dialog again. A platform rule, not a gap here.
 */
class CaptureSessionLauncher internal constructor(
    private val context: Context,
    private val capture: MediaProjectionScreenCapture,
    private val requestConsent: (Intent) -> Unit,
) {

    fun start() {
        val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return
        capture.markRequesting()
        requestConsent(manager.createScreenCaptureIntent())
    }

    fun stop() {
        context.startService(ScreenCaptureService.stopIntent(context))
        capture.stop()
    }
}

@Composable
fun rememberCaptureSessionLauncher(
    capture: MediaProjectionScreenCapture,
): CaptureSessionLauncher {
    val context = LocalContext.current

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val consent = result.data
        if (result.resultCode == Activity.RESULT_OK && consent != null) {
            // The service has to reach the foreground before it may obtain the
            // projection, so the consent is handed to it rather than used here.
            ContextCompat.startForegroundService(
                context,
                ScreenCaptureService.startIntent(context, result.resultCode, consent),
            )
        } else {
            capture.stop()
        }
    }

    return remember(context, capture) {
        CaptureSessionLauncher(
            context = context,
            capture = capture,
            requestConsent = consentLauncher::launch,
        )
    }
}
