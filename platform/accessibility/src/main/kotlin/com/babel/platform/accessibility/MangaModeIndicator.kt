package com.babel.platform.accessibility

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.babel.core.common.BabelLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a notification up for as long as manga mode is reading the screen.
 *
 * MediaProjection used to force one, and `docs/systems/privacy.md` treated that
 * as a feature rather than a nuisance: it is what made screen reading visible
 * for as long as it lasted. Taking pictures through the accessibility service
 * removes that obligation and, with it, every trace the user could see.
 *
 * So the notification is posted on purpose. The platform stopped asking for
 * visibility; the project has not (ADR 009).
 *
 * It is an ordinary notification rather than a foreground service's, because
 * there is no longer a service to put in the foreground — the accessibility
 * service the system already keeps alive is what does the reading.
 */
@Singleton
class MangaModeIndicator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: BabelLogger,
) {

    private val notifications = NotificationManagerCompat.from(context)

    fun show() {
        if (!canPost()) {
            // Worth saying out loud: the user turned notifications off, so the
            // one visible sign that the screen is being read is not there.
            logger.warn(TAG, "manga mode is on but its notification cannot be posted")
            return
        }

        createChannel()

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.manga_indicator_title))
            .setContentText(context.getString(R.string.manga_indicator_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        try {
            notifications.notify(NOTIFICATION_ID, notification)
        } catch (denied: SecurityException) {
            // [canPost] checked a moment ago, but the permission can be revoked
            // in between. Losing the indicator must not take manga mode down.
            logger.warn(TAG, "not allowed to post the manga mode notification", denied)
        }
    }

    fun hide() {
        runCatching { notifications.cancel(NOTIFICATION_ID) }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.manga_indicator_channel),
            // Low: it must be present and dismissable-proof, not attention-grabbing.
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private companion object {
        const val TAG = "MangaMode"
        const val CHANNEL_ID = "babel.manga"
        const val NOTIFICATION_ID = 0x6D61
    }
}
