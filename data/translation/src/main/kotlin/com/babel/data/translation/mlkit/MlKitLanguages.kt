package com.babel.data.translation.mlkit

import com.babel.core.model.LanguageTag
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Translates between project-owned [LanguageTag]s and ML Kit's language codes.
 *
 * Kept separate from the translator itself so the mapping — the part that has
 * real edge cases, like `zh-Hans-CN` collapsing to `zh` — can be exercised
 * without instantiating an on-device model.
 */
object MlKitLanguages {

    /** Null when ML Kit has no model for this language. */
    fun toMlKitCode(tag: LanguageTag): String? =
        TranslateLanguage.fromLanguageTag(tag.value)

    /** Every language the on-device translator can handle. */
    fun supportedTags(): List<LanguageTag> =
        TranslateLanguage.getAllLanguages().map(::LanguageTag)
}
