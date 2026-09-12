package com.babel.core.model

/** Presentation hints produced by the domain, applied by a renderer. */
data class StyleHints(
    val preferredTextSizeSp: Float? = null,
    val maxLines: Int? = null,
    /** Renderer may shrink text to fit the original bounds. */
    val allowShrinkToFit: Boolean = true,
)

/**
 * The final, render-ready unit handed to a renderer. Carries [revision] so a
 * renderer can drop an update that is already stale.
 */
data class RenderedTranslation(
    val elementId: TextElementId,
    val revision: Revision,
    val text: String,
    val bounds: TextBounds,
    val style: StyleHints = StyleHints(),
)
