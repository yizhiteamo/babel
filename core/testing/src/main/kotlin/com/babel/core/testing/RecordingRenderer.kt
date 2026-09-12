package com.babel.core.testing

import com.babel.core.model.RenderedTranslation
import com.babel.core.model.TextElementId
import com.babel.domain.render.RenderUpdate
import com.babel.domain.render.TranslationRenderer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Captures render updates and keeps a running view of what would be on screen,
 * so tests can assert that scrolling leaves no stale overlay behind.
 */
class RecordingRenderer : TranslationRenderer {

    val updates: MutableList<RenderUpdate> = CopyOnWriteArrayList()

    private val lock = Any()
    private val onScreen = LinkedHashMap<TextElementId, RenderedTranslation>()

    /** What a real renderer would currently be showing. */
    val visible: Map<TextElementId, RenderedTranslation>
        get() = synchronized(lock) { LinkedHashMap(onScreen) }

    val visibleText: List<String>
        get() = synchronized(lock) { onScreen.values.map { it.text } }

    override suspend fun apply(update: RenderUpdate) {
        updates += update
        synchronized(lock) {
            when (update) {
                is RenderUpdate.Show -> update.translations.forEach { onScreen[it.elementId] = it }
                is RenderUpdate.Hide -> update.ids.forEach { onScreen.remove(it) }
                RenderUpdate.ClearAll -> onScreen.clear()
            }
        }
    }

    fun reset() {
        updates.clear()
        synchronized(lock) { onScreen.clear() }
    }
}
