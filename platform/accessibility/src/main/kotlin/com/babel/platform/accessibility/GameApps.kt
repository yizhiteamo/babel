package com.babel.platform.accessibility

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.babel.core.common.BabelLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether an app is a game, as the app itself declares.
 *
 * `ApplicationInfo.category` is the general answer: a game's manifest says so,
 * whichever game it is. Reading another app's info needs package visibility
 * on Android 11+, which the manifest's `<queries>` grants for launcher apps
 * (`docs/milestones/v3.md`, spike 1). Games that do not declare themselves
 * will need to be added by hand — not built yet.
 */
@Singleton
class GameApps @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: BabelLogger,
) {
    private val known = ConcurrentHashMap<String, Boolean>()

    fun isGame(packageName: String): Boolean = known.getOrPut(packageName) {
        val info = try {
            context.packageManager.getApplicationInfo(packageName, 0)
        } catch (missing: PackageManager.NameNotFoundException) {
            null
        }
        @Suppress("DEPRECATION")
        val game = info != null &&
            (info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0)
        logger.info(TAG, "$packageName is ${if (game) "" else "not "}a game (category ${info?.category})")
        game
    }

    private companion object {
        const val TAG = "GameApps"
    }
}
