package com.babel.core.model

/**
 * A credential for a remote translation provider.
 *
 * A type of its own, rather than a `String` on the settings object, for one
 * reason: [toString] never reveals it. Settings are passed around, held in UI
 * state and occasionally logged, and this project already treats accidental
 * disclosure as the thing to design against rather than to remember about
 * (`docs/systems/privacy.md`). A key that cannot print itself cannot leak that
 * way.
 *
 * Read [value] deliberately, at the one point that builds the request.
 */
@JvmInline
value class ApiKey(val value: String) {

    val isPresent: Boolean get() = value.isNotBlank()

    override fun toString(): String = if (isPresent) "ApiKey(set)" else "ApiKey(unset)"
}
