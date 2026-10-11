package com.babel.domain.translation

import com.babel.core.model.LanguagePair
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
        /**
         * Bumped to 2 when the page's own words started travelling with each
         * request ([PageContext]). The prompt changed, so entries made under
         * the old one answer a different question — and context deliberately
         * stays out of the key, because "the same line keeps the rendering it
         * was first given" is the consistency this exists to buy.
         *
         * 3 when a game's speaker names began to be sent as names: entries
         * from before answered them as words (「ヒナ」 as 「雏」), and the
         * durable labels kept on disk would have kept those answers.
         *
         * 4 when the game's identity joined the prompt, for official names.
         *
         * 5 when answers still in Japanese began to be asked again: a chat's
         * short replies had been kept on disk corrected, not translated
         * (「ちょっと困って」), and as labels would have stayed so.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 5

        private val WHITESPACE = Regex("\\s+")

        /**
         * The only way a key should be built, so text normalization cannot
         * drift between the coordinator and the cache implementation.
         */
        fun of(
            sourceText: String,
            languages: LanguagePair,
            provider: ProviderId,
        ): TranslationCacheKey = TranslationCacheKey(
            normalizedText = normalizeText(sourceText),
            sourceLanguage = languages.source,
            targetLanguage = languages.target,
            provider = provider,
        )

        /**
         * Re-wrapped text differs only in whitespace but means the same thing;
         * collapsing it keeps a scrolling reader from re-translating the same
         * paragraph at every layout pass.
         */
        fun normalizeText(text: String): String = text.trim().replace(WHITESPACE, " ")
    }
}

/**
 * Reached only through the translation layer — UI and acquisition never touch
 * the cache directly.
 */
interface TranslationCache {
    suspend fun get(key: TranslationCacheKey): String?

    suspend fun put(key: TranslationCacheKey, translatedText: String)

    /**
     * Like [put], and kept across restarts: a game's names and interface
     * words, so that 「レフ」 is the same name tomorrow — reinstalled, the
     * in-memory cache came back with 「列夫」 where it had said 「雷夫」.
     * Only labels, never dialogue (`docs/systems/privacy.md`). An
     * implementation that keeps nothing on disk may treat it as [put].
     */
    suspend fun putDurable(key: TranslationCacheKey, translatedText: String) = put(key, translatedText)

    suspend fun clear()
}
