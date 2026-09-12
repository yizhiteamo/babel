package com.babel.domain.scope

/**
 * Decides whether an app is worth translating at all.
 *
 * Distinct from [com.babel.domain.privacy.SensitiveContentPolicy]: scope asks
 * "is translating this useful?", privacy asks "may this text leave the screen?"
 * A launcher is out of scope because translating proper nouns helps nobody, not
 * because icon labels are secret (`docs/systems/scope.md`).
 *
 * Takes a package name rather than a `TextElement` — scope is a property of the
 * app, and an interface should not be wider than the thing it decides. That
 * also lets acquisition check it once per scan instead of once per element.
 */
interface TranslationScopePolicy {

    /** Null means the source app is unknown; unknown sources stay in scope. */
    fun isInScope(packageName: String?): Boolean
}

/**
 * Excludes a fixed set of packages.
 *
 * The set is supplied rather than resolved here: which package is the launcher
 * or the keyboard is a platform question, and this module is pure Kotlin.
 */
class DefaultTranslationScopePolicy(
    private val excludedPackages: Set<String>,
) : TranslationScopePolicy {

    override fun isInScope(packageName: String?): Boolean {
        if (packageName == null) return true
        return packageName !in excludedPackages
    }
}
