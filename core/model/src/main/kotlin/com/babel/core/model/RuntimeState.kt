package com.babel.core.model

/**
 * What the translator is currently doing. Deliberately not a boolean
 * (`docs/systems/runtime-state.md`).
 *
 * Distinct from [CapabilityState], which answers whether the system *could*
 * run at all.
 */
sealed interface TranslationRuntimeState {
    data object Disabled : TranslationRuntimeState

    data object Starting : TranslationRuntimeState

    data object Running : TranslationRuntimeState

    data object Paused : TranslationRuntimeState

    data class Error(val error: TranslationError) : TranslationRuntimeState
}
