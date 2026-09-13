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

    /**
     * The user paused the **text** path. Newly acquired accessibility text is
     * not translated; what is already drawn stays until the content changes.
     *
     * Manga mode is unaffected, because it is a separate switch the user turns
     * on deliberately. One coordinator still owns this state — pausing narrows
     * what it acts on, it does not split the state in two
     * (`docs/systems/runtime-state.md`).
     */
    data object Paused : TranslationRuntimeState

    data class Error(val error: TranslationError) : TranslationRuntimeState
}
