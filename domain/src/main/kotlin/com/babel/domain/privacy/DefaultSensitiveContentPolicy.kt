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

        return true
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
        const val MIN_MASKED_LENGTH = 4
        const val MIN_SENSITIVE_DIGITS = 12
        val MASK_CHARS = setOf('*', '•', '●', '·', '∙', '‧', '＊', '○', '◦')
    }
}
