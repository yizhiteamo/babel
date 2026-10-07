package com.babel.app.tile

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Babel's tile is in quick settings, as far as the tile has said.
 *
 * Android gives an app no way to ask, and none to remove its own tile — that
 * is the user's, through Edit in the control centre. What it does give is the
 * tile's own callbacks, so the tile reports here and the home screen shows an
 * add button only to someone who has not added it, and how to remove it to
 * someone who has.
 *
 * Interface state rather than a user setting, so not in the settings
 * repository.
 */
@Singleton
class TilePresence @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private val _added = MutableStateFlow(preferences.getBoolean(KEY_ADDED, false))
    val added: StateFlow<Boolean> = _added.asStateFlow()

    fun report(added: Boolean) {
        if (_added.value == added) return
        _added.value = added
        preferences.edit().putBoolean(KEY_ADDED, added).apply()
    }

    /** Re-reads what the tile wrote, in case it was removed while the app was away. */
    fun refresh() {
        _added.value = preferences.getBoolean(KEY_ADDED, false)
    }

    private companion object {
        const val PREFERENCES = "babel.tile"
        const val KEY_ADDED = "added"
    }
}
