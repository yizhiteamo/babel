package com.babel.core.model

/**
 * How a line of text is set, which determines both how lines group into bubbles
 * and the order they read in.
 *
 * A page mixes both — vertical dialogue beside horizontal captions or sound
 * effects — so this belongs to each line, never to the page.
 *
 * Lives here rather than beside the detector that produces it because a
 * renderer needs it: a translation replacing vertical dialogue has to be set
 * vertically too, and `:core:model` is the only place both the domain and the
 * platform can see.
 */
enum class TextOrientation {
    /** Characters run top to bottom; columns are ordered right to left (CJK). */
    VERTICAL,

    /** Characters run left to right; lines are ordered top to bottom. */
    HORIZONTAL,
}
