package com.babel.core.model

/**
 * What the translator is currently doing. Deliberately not a boolean
 * (`docs/systems/runtime-state.md`).
 *
 * Distinct from [CapabilityState], which answers whether the system *could*
 * run at all.
 *
 * ## Why there is no `Error` here
 *
 * There was one, and nothing ever set it. It was also the wrong shape: a
 * provider that rejects a key has not changed what the **coordinator** is
 * doing — it is still running, still accepting elements, still rendering what
 * the cache can answer. Putting that failure in this enum breaks the controls
 * that read it: `pause()` only acts while `Running`, and `canTogglePause` only
 * offers the button for `Running` or `Paused`. The pause button would vanish at
 * exactly the moment somebody is trying to fix something.
 *
 * Provider health is a second, orthogonal axis and lives on its own:
 * `TranslationCoordinator.providerFailure`.
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
}

