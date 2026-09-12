package com.babel.platform.overlay

import android.content.Context
import com.babel.core.common.BabelLogger
import com.babel.core.common.DispatcherProvider
import com.babel.domain.render.RenderUpdate
import com.babel.domain.render.TranslationRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

/**
 * Draws what the coordinator publishes, and nothing more — no decisions about
 * what to translate, no provider access (ADR 005).
 *
 * Every update hops to the main thread because window and view mutation
 * requires it, while the coordinator runs its pipeline off the main thread.
 */
@Singleton
class OverlayRenderer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: DispatcherProvider,
    private val logger: BabelLogger,
) : TranslationRenderer {

    private val window by lazy { OverlayWindow(context) }

    override suspend fun apply(update: RenderUpdate) = withContext(dispatchers.main) {
        when (update) {
            is RenderUpdate.Show -> {
                if (!ensureAttached()) return@withContext
                window.show(update.translations)
            }

            is RenderUpdate.Hide -> window.hide(update.ids)

            // Detach rather than just emptying the container: with nothing to
            // show, keeping a window over every other app earns nothing.
            RenderUpdate.ClearAll -> window.detach()
        }
    }

    private fun ensureAttached(): Boolean {
        if (window.isAttached) return true
        val attached = window.attach()
        if (!attached) {
            logger.warn(TAG, "overlay permission is not granted; nothing can be drawn")
        }
        return attached
    }

    private companion object {
        const val TAG = "OverlayRenderer"
    }
}
