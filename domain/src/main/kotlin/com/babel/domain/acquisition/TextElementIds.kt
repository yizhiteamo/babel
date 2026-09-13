package com.babel.domain.acquisition

import com.babel.core.model.TextElementId
import com.babel.domain.translation.TranslationCacheKey

/**
 * Builds element identity from content.
 *
 * Shared by every acquisition method rather than reimplemented per source: the
 * coordinator reuses an existing translation only when the id matches, so two
 * sources deriving ids differently would re-translate the same sentence.
 *
 * **Identity comes from text, never from position.** A scroll moves text
 * without changing it; deriving ids from bounds would make every scroll look
 * like new content and re-translate the screen.
 *
 * The id carries a hash rather than the text, because ids reach diagnostics and
 * screen text must not (`docs/systems/privacy.md`).
 */
object TextElementIds {

    /**
     * @param scope separates ids that could otherwise collide across contexts —
     *   a window id for accessibility, a page or session marker for OCR.
     * @param occurrence distinguishes repeated text on one screen, in the order
     *   it was encountered.
     */
    fun forContent(scope: String, text: String, occurrence: Int): TextElementId {
        val normalized = TranslationCacheKey.normalizeText(text)
        val hash = normalized.hashCode().toUInt().toString(16).padStart(HASH_WIDTH, '0')
        return TextElementId("$scope:$hash:$occurrence")
    }

    private const val HASH_WIDTH = 8
}
