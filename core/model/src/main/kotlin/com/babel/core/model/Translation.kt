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
    /**
     * Whether [sourceText] was read from pixels and may hold misread
     * characters. Accessibility text is exact; OCR's is a reading.
     */
    val recognised: Boolean = false,
    /**
     * Where the text was shown, so a provider can be told what it is
     * translating: a game's 「AUTO」, sent as a comic's speech balloon, came
     * back as the word for a car.
     */
    val sourceType: TextSourceType? = null,
    /** Hints from the acquisition layer, carried from [TextElement.metadata]; see [Hints]. */
    val metadata: Map<String, String> = emptyMap(),
) {
    object Hints {
        /** The text is a character's name shown over their line. */
        const val SPEAKER = "speaker"
    }
}

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
