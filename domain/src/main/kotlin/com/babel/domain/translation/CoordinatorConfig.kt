package com.babel.domain.translation

/**
 * Tuning for [DefaultTranslationCoordinator].
 *
 * [maxConcurrentTranslations] exists because a single scroll can reveal dozens
 * of elements at once; firing them all would swamp the provider and delay the
 * text the user is actually looking at.
 */
data class CoordinatorConfig(
    val maxRetries: Int = 2,
    val retryBaseDelayMillis: Long = 300,
    val maxConcurrentTranslations: Int = 4,
) {
    init {
        require(maxRetries >= 0) { "maxRetries must not be negative" }
        require(retryBaseDelayMillis >= 0) { "retryBaseDelayMillis must not be negative" }
        require(maxConcurrentTranslations > 0) { "maxConcurrentTranslations must be positive" }
    }

    /** Exponential backoff: 300ms, 600ms, 1200ms, … */
    fun retryDelayMillis(attempt: Int): Long =
        retryBaseDelayMillis shl (attempt - 1).coerceAtLeast(0)
}
