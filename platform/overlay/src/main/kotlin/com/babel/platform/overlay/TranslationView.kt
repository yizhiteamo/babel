package com.babel.platform.overlay

import android.view.View
import com.babel.core.model.RenderedTranslation

/**
 * What the overlay needs of a view, so it can hold horizontal and vertical
 * translations in the same map without knowing which it has.
 *
 * There are two because Android has no vertical text: a `TextView` handles the
 * horizontal case well, including autosizing, and reproducing that to gain
 * vertical support would be a step backwards. So the vertical case is a
 * separate, self-drawn view and this is the seam between them.
 */
internal interface TranslationView {

    val view: View

    fun bind(translation: RenderedTranslation)
}
