package com.babel.domain.render

import com.babel.core.model.RenderedTranslation
import com.babel.core.model.TextElementId

/** The only vocabulary the domain uses to drive a renderer. */
sealed interface RenderUpdate {
    data class Show(val translations: List<RenderedTranslation>) : RenderUpdate

    data class Hide(val ids: List<TextElementId>) : RenderUpdate

    data object ClearAll : RenderUpdate
}

/**
 * Implemented by `:platform:overlay`. Renderers never call a translation
 * provider and never decide *what* to translate — they draw what they are given
 * and drop stale revisions.
 */
interface TranslationRenderer {
    suspend fun apply(update: RenderUpdate)
}
