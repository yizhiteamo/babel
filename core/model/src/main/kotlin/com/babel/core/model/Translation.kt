package com.babel.core.model

@JvmInline
value class RequestId(val value: String)

/** Identifies a translation provider without naming its SDK. */
@JvmInline
value class ProviderId(val value: String)

data class TranslationRequest(
    val requestId: RequestId,
    val elementId: TextElementId,
    val revision: Revision,
    val sourceText: String,
    val languages: LanguagePair,
    /** Optional surrounding text a provider may use; not persisted. */
    val context: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

data class TranslationResult(
    val requestId: RequestId,
    val elementId: TextElementId,
    val revision: Revision,
    val originalText: String,
    val translatedText: String,
    val detectedSourceLanguage: LanguageTag? = null,
    val provider: ProviderId,
    val status: TranslationStatus,
)

sealed interface TranslationStatus {
    /** Provider returned translated text. */
    data object Translated : TranslationStatus

    /** Source and target languages matched; nothing to render. */
    data object Unchanged : TranslationStatus

    data class Failed(val error: TranslationError) : TranslationStatus
}
