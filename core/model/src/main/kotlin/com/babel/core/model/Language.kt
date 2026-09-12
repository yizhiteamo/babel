package com.babel.core.model

/** A BCP-47 language tag, e.g. `en`, `ja`, `zh-Hans`. */
@JvmInline
value class LanguageTag(val value: String)

/**
 * How the source language is decided. Defaults to [AutoDetect]; the app never
 * assumes a fixed source language (ADR 003).
 */
sealed interface SourceLanguageMode {
    data object AutoDetect : SourceLanguageMode

    data class Manual(val language: LanguageTag) : SourceLanguageMode
}

/**
 * How the target language is decided. Defaults to [FollowSystem].
 *
 * This is the *translation* target and is a separate concept from the language
 * the app's own UI is displayed in.
 */
sealed interface TargetLanguageMode {
    data object FollowSystem : TargetLanguageMode

    data class Manual(val language: LanguageTag) : TargetLanguageMode
}

/**
 * A resolved pair ready to be sent to a provider.
 *
 * [source] is null when detection is delegated to the provider.
 */
data class LanguagePair(
    val source: LanguageTag?,
    val target: LanguageTag,
)
