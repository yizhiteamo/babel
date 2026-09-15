package com.babel.platform.capture

import android.graphics.Bitmap
import com.babel.core.model.TextBounds

/**
 * Finds the speech balloons on a page, before anything tries to read them.
 *
 * This exists because grouping recognised lines into bubbles by their positions
 * does not work well enough, and two attempts to make it work were reverted
 * after measurement (`docs/milestones/v2.md`). A page is a picture of balloons;
 * a model trained on comics finds them directly, and knowing where a balloon is
 * settles three separate problems at once:
 *
 * - **grouping** — one balloon becomes one unit of translation, which is what a
 *   capable translator needs and what splitting it denies
 * - **filtering** — sound effects and cover lettering are not balloons, so they
 *   stop being offered for translation
 * - **placement** — the balloon's own outline beats growing a rectangle out of
 *   the text until the pixels stop matching
 */
internal interface TextDetector {

    /**
     * Whether this detector can run at all.
     *
     * False rather than throwing, because the caller has a working path without
     * it and should take that path quietly. A missing model is a normal state,
     * not a failure.
     */
    val isAvailable: Boolean

    /**
     * @return one entry per balloon found, in no particular order.
     */
    suspend fun detect(frame: Bitmap): List<DetectedBubble>
}

/**
 * A balloon, as the detector sees it.
 *
 * Two rectangles because the model reports two things and both are useful: the
 * lettering to read, and the balloon to draw the translation into. They are
 * rarely the same shape — a balloon is larger than the text it holds, which is
 * exactly the margin a translation needs.
 */
internal data class DetectedBubble(
    /** The lettering. What gets cropped and read. */
    val text: TextBounds,
    /** The balloon around it, when the detector found one to match. */
    val balloon: TextBounds?,
)
