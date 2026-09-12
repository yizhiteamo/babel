package com.babel.domain.language

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode

/** Supplies the current system language. Implemented on the platform side. */
interface SystemLocaleProvider {
    fun current(): LanguageTag
}

/**
 * The single owner of locale policy: `FollowSystem` resolution, tag
 * normalization, and supported-language validation. No other module reimplements
 * this (`docs/systems/language.md`).
 */
interface LanguageResolver {
    fun resolve(
        source: SourceLanguageMode,
        target: TargetLanguageMode,
    ): LanguagePair

    fun normalize(tag: LanguageTag): LanguageTag

    fun isSupported(tag: LanguageTag): Boolean

    fun supportedLanguages(): List<LanguageTag>
}
