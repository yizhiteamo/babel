package com.babel.domain.privacy

import com.babel.core.model.TextElement

/**
 * Gate in front of the pipeline. Anything rejected here never reaches a
 * provider, the cache, or a log (`docs/systems/privacy.md`).
 *
 * The policy errs toward excluding: a missed translation is a minor annoyance,
 * a leaked credential is not. Every rule below rejects text that would be
 * meaningless to translate anyway, so the cost of a false positive is low.
 *
 * [excludedPackages] lets a user keep whole apps out of translation — banking,
 * password managers — without disabling the service.
 */
class DefaultSensitiveContentPolicy(
    private val excludedPackages: Set<String> = emptySet(),
) : SensitiveContentPolicy {

    override fun isTranslatable(element: TextElement): Boolean {
        if (element.isProtected) return false

        val packageName = element.source.packageName
        if (packageName != null && packageName in excludedPackages) return false

        val text = element.text
        if (text.isBlank()) return false
        if (isMasked(text)) return false
        if (isLongDigitSequence(text)) return false
        if (isAddress(text)) return false

        return true
    }

    /**
     * A URL or a file path: meaningless to translate, and revealing to send.
     *
     * Both halves of this class's reasoning point the same way. Nothing is lost
     * by skipping an address — a translator hands it straight back — and what
     * would be sent is the user's browsing and their file names, which is not
     * something a translation needs.
     *
     * It also closes a defect the narrow providers hid. A chat model given the
     * browser's address bar replied **to the reader** — "I can't access files
     * on your device. Please paste the text…" — and that sentence was drawn in
     * an opaque box over the address bar, on every page load
     * (`docs/milestones/v2.md`). The provider's instruction was hardened to
     * echo such input instead, but an instruction is a request to somebody
     * else's model; this is the half that does not depend on it.
     *
     * Deliberately narrow: a scheme, or an absolute path. `example.com` on its
     * own is left alone — it is as likely to be a word on a page as an address,
     * and `12:30` must stay a time.
     */
    private fun isAddress(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.any(Char::isWhitespace)) return false
        if (SCHEME.containsMatchIn(trimmed)) return true
        return trimmed.startsWith('/') && trimmed.count { it == '/' } >= 2
    }

    /**
     * A password field that has lost focus often still exposes its bullet
     * characters through the accessibility tree. `isProtected` alone does not
     * catch those.
     */
    private fun isMasked(text: String): Boolean {
        val stripped = text.filterNot(Char::isWhitespace)
        return stripped.length >= MIN_MASKED_LENGTH && stripped.all { it in MASK_CHARS }
    }

    /**
     * Card numbers, account numbers, long verification codes. Digits are
     * identical in every language, so nothing is lost by skipping them.
     */
    private fun isLongDigitSequence(text: String): Boolean {
        val digitsOnly = text.filterNot { it.isWhitespace() || it == '-' || it == '_' }
        return digitsOnly.length >= MIN_SENSITIVE_DIGITS && digitsOnly.all(Char::isDigit)
    }

    private companion object {
        /** `scheme://` at the start — http, https, file, content, ftp, … */
        val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

        const val MIN_MASKED_LENGTH = 4
        const val MIN_SENSITIVE_DIGITS = 12
        val MASK_CHARS = setOf('*', '•', '●', '·', '∙', '‧', '＊', '○', '◦')
    }
}
