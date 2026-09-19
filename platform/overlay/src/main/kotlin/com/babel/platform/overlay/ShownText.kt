package com.babel.platform.overlay

import com.babel.core.model.RenderedTranslation

/**
 * Which of the two texts a balloon should be showing.
 *
 * A tap on a translated speech balloon swaps to the original and back. Doing it
 * by handing the views a different `text` — rather than teaching them about two
 * strings — is what keeps the swap free: both already lay out whatever they are
 * given, so vertical Japanese re-flows into its columns and the horizontal view
 * re-fits with no extra code.
 *
 * Refusing to swap to nothing is part of the same decision rather than a
 * separate guard at the call site: an element whose original text never arrived
 * would otherwise toggle into an empty balloon, which reads as a bug.
 *
 * **Apply this to the bound translation every time, never to its own result.**
 * Swapping produces a model whose text *is* the original, so asking that one
 * for the translation again cannot work — nothing holds it any more. `Held`
 * keeps the bound model and re-derives from it on each toggle, which is what
 * makes the toggle reversible.
 */
internal fun RenderedTranslation.showing(original: Boolean): RenderedTranslation =
    if (original && originalText.isNotBlank()) copy(text = originalText) else this
