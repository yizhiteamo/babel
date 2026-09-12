package com.babel.platform.accessibility

import com.babel.core.model.Revision
import com.babel.core.model.SourceIdentity
import com.babel.core.model.TextElement
import com.babel.core.model.TextElementId
import com.babel.core.model.TextSourceType
import com.babel.domain.translation.TranslationCacheKey

/**
 * Assigns identity to acquired text.
 *
 * **Identity is derived from content, not position.** A scroll moves text
 * without changing it, and the coordinator reuses an existing translation only
 * when the id and the source text both match. Deriving the id from bounds
 * instead would make every scroll look like new content and re-translate the
 * whole screen.
 *
 * Repeated text on one screen (several "OK" buttons) is separated by
 * occurrence index, in tree order, so each keeps a distinct overlay.
 *
 * The id carries a hash rather than the text itself, because ids appear in
 * diagnostics and raw screen text must not (`docs/systems/privacy.md`).
 */
object TextElementFactory {

    fun create(
        rawTexts: List<RawText>,
        windowId: Int,
        packageName: String?,
        revision: Revision,
    ): List<TextElement> {
        val occurrences = mutableMapOf<String, Int>()

        return rawTexts.map { raw ->
            val normalized = TranslationCacheKey.normalizeText(raw.text)
            val index = occurrences.getOrDefault(normalized, 0)
            occurrences[normalized] = index + 1

            TextElement(
                id = idFor(windowId, normalized, index),
                text = raw.text,
                bounds = raw.bounds,
                sourceType = TextSourceType.ACCESSIBILITY,
                source = SourceIdentity(packageName = packageName, windowId = windowId),
                revision = revision,
                isProtected = raw.isPassword,
            )
        }
    }

    private fun idFor(windowId: Int, normalizedText: String, occurrence: Int): TextElementId {
        val hash = normalizedText.hashCode().toUInt().toString(16).padStart(8, '0')
        return TextElementId("$windowId:$hash:$occurrence")
    }
}
