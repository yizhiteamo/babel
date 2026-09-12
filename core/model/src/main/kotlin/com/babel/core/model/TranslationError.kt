package com.babel.core.model

/**
 * Classified failure. A provider failure must degrade the affected element only
 * — it must never take down acquisition, rendering, or the UI
 * (`docs/architecture.md`, Error Boundaries).
 */
sealed interface TranslationError {
    val retryable: Boolean

    /** Superseded by newer visible content, or the pipeline was stopped. */
    data object Cancelled : TranslationError {
        override val retryable: Boolean get() = false
    }

    data object Offline : TranslationError {
        override val retryable: Boolean get() = true
    }

    data class Network(val cause: String? = null) : TranslationError {
        override val retryable: Boolean get() = true
    }

    data object RateLimited : TranslationError {
        override val retryable: Boolean get() = true
    }

    /** Missing key, rejected request, unsupported language pair, etc. */
    data class ProviderRejected(
        val provider: ProviderId,
        val reason: String? = null,
    ) : TranslationError {
        override val retryable: Boolean get() = false
    }

    data class Unsupported(val reason: String? = null) : TranslationError {
        override val retryable: Boolean get() = false
    }

    data class Unexpected(val cause: String? = null) : TranslationError {
        override val retryable: Boolean get() = false
    }
}
