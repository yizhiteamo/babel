package com.babel.domain.privacy

import com.babel.core.testing.TestElements
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DefaultSensitiveContentPolicyTest {

    private val policy = DefaultSensitiveContentPolicy()

    @Test
    fun `ordinary prose is translatable`() {
        assertTrue(policy.isTranslatable(TestElements.element(text = "The quick brown fox")))
    }

    @Test
    fun `protected input is excluded`() {
        assertFalse(policy.isTranslatable(TestElements.element(text = "hunter2", isProtected = true)))
    }

    @Test
    fun `masked password text is excluded even when not flagged protected`() {
        assertFalse(policy.isTranslatable(TestElements.element(text = "••••••••")))
        assertFalse(policy.isTranslatable(TestElements.element(text = "********")))
    }

    @Test
    fun `a short run of asterisks is not treated as a password`() {
        // Emphasis markers and footnote markers are legitimate content.
        assertTrue(policy.isTranslatable(TestElements.element(text = "**")))
    }

    @Test
    fun `card-length digit sequences are excluded`() {
        assertFalse(policy.isTranslatable(TestElements.element(text = "4111 1111 1111 1111")))
        assertFalse(policy.isTranslatable(TestElements.element(text = "4111-1111-1111-1111")))
    }

    @Test
    fun `short numbers such as prices and years stay translatable`() {
        assertTrue(policy.isTranslatable(TestElements.element(text = "2026")))
        assertTrue(policy.isTranslatable(TestElements.element(text = "1234")))
    }

    @Test
    fun `blank text is excluded`() {
        assertFalse(policy.isTranslatable(TestElements.element(text = "   ")))
    }

    @Test
    fun `excluded packages are skipped entirely`() {
        val guarded = DefaultSensitiveContentPolicy(excludedPackages = setOf("com.bank.app"))

        assertFalse(
            guarded.isTranslatable(
                TestElements.element(text = "Account balance", packageName = "com.bank.app"),
            ),
        )
        assertTrue(
            guarded.isTranslatable(
                TestElements.element(text = "Account balance", packageName = "com.example.reader"),
            ),
        )
    }

    @Test
    fun `text mixing digits and words is not mistaken for an account number`() {
        assertTrue(
            policy.isTranslatable(
                TestElements.element(text = "Order 1234567890123 has shipped"),
            ),
        )
    }
}
