package com.babel.core.model

/** Presentation hints produced by the domain, applied by a renderer. */
data class StyleHints(
    val preferredTextSizeSp: Float? = null,
    val maxLines: Int? = null,
    /** Renderer may shrink text to fit the original bounds. */
    val allowShrinkToFit: Boolean = true,
    /**
     * Colours sampled from the source, when they are known. A renderer that
     * gets them should prefer them over any theme guess: matching the actual
     * background is what makes an overlay read as replacement rather than as a
     * panel sitting on top (`docs/decisions/008`).
     */
    val sourceStyle: SourceStyle = SourceStyle.UNKNOWN,
)

/**
 * The final, render-ready unit handed to a renderer. Carries [revision] so a
 * renderer can drop an update that is already stale.
 */
data class RenderedTranslation(
    val elementId: TextElementId,
    val revision: Revision,
    val text: String,
    /**
     * The text this replaces, so a reader can ask to see it.
     *
     * Carried for the renderer's benefit rather than the pipeline's: tapping a
     * translated speech balloon swaps to this and back, which needs the words
     * at the moment of the tap — long after the result that produced them has
     * gone.
     *
     * Not a new exposure. Protected text never becomes a `RenderedTranslation`
     * at all: the privacy policy rejects it before any provider is called
     * (`docs/systems/privacy.md`). It is still screen content, so it follows the
     * same rule as the rest — **never pass it to a logger**, with or without
     * `Redact`.
     */
    val originalText: String = "",
    val bounds: TextBounds,
    val style: StyleHints = StyleHints(),
    /**
     * Where the text being replaced came from.
     *
     * A renderer needs it: replacing text in a live app and replacing it in a
     * captured image are not the same job. The first must let every touch
     * through and therefore cannot be opaque; the second may take the touches
     * that land on it and therefore can be (ADR 008's amendment).
     */
    val sourceType: TextSourceType = TextSourceType.ACCESSIBILITY,
)
