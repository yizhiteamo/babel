package com.babel.core.model

/**
 * Visual properties sampled from the source, when the acquisition method can
 * see them.
 *
 * Only the OCR path can: it holds the actual pixels. Accessibility reports no
 * colours at all, which is why V1 has to guess a background and why its
 * overlays never quite match the app underneath (`docs/decisions/008`).
 *
 * Colours are packed ARGB, as Android represents them. Null means "not known",
 * never "transparent" — a renderer must fall back rather than paint nothing.
 */
data class SourceStyle(
    val backgroundColor: Int? = null,
    val foregroundColor: Int? = null,
) {
    val isEmpty: Boolean get() = backgroundColor == null && foregroundColor == null

    companion object {
        val UNKNOWN = SourceStyle()
    }
}
