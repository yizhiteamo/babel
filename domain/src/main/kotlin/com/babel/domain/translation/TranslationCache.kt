package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId

/**
 * Cache identity. Source text alone is never a valid key: the same sentence
 * translated to a different target language, or by a different provider, is a
 * different entry (`docs/systems/cache.md`).
 *
 * [schemaVersion] is bumped to invalidate incompatible entries explicitly.
 */
data class TranslationCacheKey(
    val normalizedText: String,
    val sourceLanguage: LanguageTag?,
    val targetLanguage: LanguageTag,
    val provider: ProviderId,
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * Reached only through the translation layer — UI and acquisition never touch
 * the cache directly.
 */
interface TranslationCache {
    suspend fun get(key: TranslationCacheKey): String?

    suspend fun put(key: TranslationCacheKey, translatedText: String)

    suspend fun clear()
}
