package com.babel.domain.privacy

import com.babel.core.model.TextElement

/**
 * Applied before an element enters the pipeline. Password-like and explicitly
 * protected input never reaches a provider, a cache, or a log
 * (`docs/systems/privacy.md`).
 */
interface SensitiveContentPolicy {
    fun isTranslatable(element: TextElement): Boolean
}
