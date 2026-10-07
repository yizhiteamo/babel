package com.babel.app.tile

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import androidx.annotation.RequiresApi
import com.babel.app.R

/** What became of asking the system to add the tile. */
enum class TileRequestResult { ADDED, ALREADY_ADDED, DECLINED, UNSUPPORTED }

/** Whether this Android can be asked to add the tile at all (13 and up). */
val canRequestTile: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/**
 * Asks the system to put Babel's tile in quick settings, with its own dialog.
 *
 * The tile was there and the user did not find it: HyperOS's control centre
 * shows tiles without their names, and a translate glyph reads as the system's
 * own. Adding it from the home screen, next to the words that say what it is,
 * is how somebody learns it exists.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
fun Context.requestAddBabelTile(onResult: (TileRequestResult) -> Unit) {
    val statusBar = getSystemService(StatusBarManager::class.java)
    if (statusBar == null) {
        onResult(TileRequestResult.UNSUPPORTED)
        return
    }
    statusBar.requestAddTileService(
        ComponentName(this, BabelTileService::class.java),
        getString(R.string.tile_label),
        Icon.createWithResource(this, R.drawable.ic_tile),
        mainExecutor,
    ) { code ->
        onResult(
            when (code) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> TileRequestResult.ADDED
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> TileRequestResult.ALREADY_ADDED
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> TileRequestResult.DECLINED
                // Every other code is an error: the system would not ask.
                else -> TileRequestResult.UNSUPPORTED
            },
        )
    }
}
