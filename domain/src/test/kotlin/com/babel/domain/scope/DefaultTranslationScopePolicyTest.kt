package com.babel.domain.scope

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DefaultTranslationScopePolicyTest {

    private val policy = DefaultTranslationScopePolicy(
        excludedPackages = setOf("app.lawnchair", "com.babel", "com.android.systemui"),
    )

    @Test
    fun `an ordinary app is in scope`() {
        assertTrue(policy.isInScope("com.example.reader"))
    }

    @Test
    fun `an excluded app is out of scope`() {
        assertFalse(policy.isInScope("app.lawnchair"))
        assertFalse(policy.isInScope("com.babel"))
        assertFalse(policy.isInScope("com.android.systemui"))
    }

    /**
     * An unknown source must not silence translation: failing open keeps the
     * product working when a window reports no package, while failing closed
     * would make text vanish for reasons the user cannot see.
     */
    @Test
    fun `an unknown package stays in scope`() {
        assertTrue(policy.isInScope(null))
    }

    @Test
    fun `matching is exact rather than by prefix`() {
        // A different app must not be caught by sharing a prefix with an
        // excluded one.
        assertTrue(policy.isInScope("com.babelfish.reader"))
        assertTrue(policy.isInScope("app.lawnchair.plugin"))
    }

    @Test
    fun `an empty exclusion set leaves everything in scope`() {
        val open = DefaultTranslationScopePolicy(excludedPackages = emptySet())

        assertTrue(open.isInScope("app.lawnchair"))
        assertTrue(open.isInScope(null))
    }
}
