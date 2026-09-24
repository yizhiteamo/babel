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

    /**
     * A URL or a file path is meaningless to translate and revealing to send.
     *
     * The case that provoked the rule: the browser's address bar reached a chat
     * model, which answered the reader — "I can't access files on your device.
     * Please paste the text…" — and that sentence was drawn over the address
     * bar (`docs/milestones/v2.md`).
     */
    @Test
    fun `addresses are excluded`() {
        for (address in listOf(
            "file:///sdcard/Download/reading-sample.html",
            "https://example.com/a/b/c?q=1",
            "http://localhost:11434/v1/chat/completions",
            "content://media/external/images/media/42",
            "/sdcard/Android/data/com.babel/files",
        )) {
            assertFalse(policy.isTranslatable(TestElements.element(text = address)), address)
        }
    }

    @Test
    fun `text that merely looks address-ish stays translatable`() {
        // Narrow on purpose: each of these is as likely to be words on a page.
        for (text in listOf(
            "example.com",
            "12:30",
            "Visit https://example.com for more",
            "/home",
            "a/b",
        )) {
            assertTrue(policy.isTranslatable(TestElements.element(text = text)), text)
        }
    }
}
