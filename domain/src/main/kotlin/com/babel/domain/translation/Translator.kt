package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult

/**
 * Provider-neutral translation interface (ADR 005).
 *
 * Implementations live in `:data:translation`. Their SDK, HTTP, and DTO types
 * must not escape that module.
 *
 * Implementations must be cancellable: callers cancel the coroutine when a
 * request is superseded.
 */
interface Translator {
    val id: ProviderId

    /** Returns a result whose status carries the failure — this does not throw. */
    suspend fun translate(request: TranslationRequest): TranslationResult

    fun supports(source: LanguageTag?, target: LanguageTag): Boolean
}
