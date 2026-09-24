package com.babel.domain.vision

import com.babel.core.model.TextBounds
import com.babel.core.model.TextOrientation

/**
 * Lines that belong together — in practice one speech bubble — with their
 * reading order already resolved.
 *
 * This is the unit that gets translated. An OCR text block is not: a recogniser
 * may split one bubble across several blocks, or merge two columns into one
 * block in the wrong order (both observed, see `docs/milestones/v2.md`).
 */
data class TextRegion(
    /** In reading order, so joining them yields readable text. */
    val lines: List<RecognizedLine>,
    /** Bounding box covering every line, used to place and erase. */
    val bounds: TextBounds,
    /**
     * How this region is set. Carried through because rendering needs it: a
     * translation replacing vertical dialogue has to be laid out differently
     * from one replacing a horizontal caption, and the information would
     * otherwise be lost once grouping is done.
     */
    val orientation: TextOrientation,
    /**
     * The balloon this region sits in, when something knows where it is.
     *
     * Not a replacement for [bounds], which stays the lettering. The two answer
     * different questions and each is better at its own: a detector knows
     * *which* balloon, but reports its bounding rectangle — and filling that
     * opaquely covers the outline and the corners outside an oval, which was
     * measured on a device and looked worse than what it replaced. Growing a box
     * out from the text until the pixels stop matching produces a rectangle that
     * sits *inside* the balloon, which is the shape wanted.
     *
     * So this is the **limit** on that growth. It is what stops the growth
     * leaking out through a balloon's tail — the failure that had the flood-fill
     * version reverted (`docs/milestones/v2.md`).
     */
    val enclosure: TextBounds? = null,
) {
    /**
     * The lines as one string, with whatever the script wants between them.
     *
     * This used to join with nothing, on the grounds that Japanese columns
     * continue one sentence — true, and wrong for every other script. See
     * [LineJoin] for what the seam is actually asked.
     */
    val text: String get() = LineJoin.join(lines.map { it.text })
}
