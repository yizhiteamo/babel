package com.babel.domain.vision

/**
 * Collects the balloons that are saying one thing between them.
 *
 * Manga cuts a sentence across balloons, and translating the halves separately
 * destroys it — `わたしの` / `めを見て` came back as 我的 and 看着我的眼睛, so
 * the first balloon said nothing (`docs/milestones/v2.md`). Joined, it is one
 * line. Joining the *wrong* pair is equally destructive: two separate
 * utterances run together lose one of themselves, measured on the same day.
 *
 * So the grouper holds a balloon only while [JapaneseClause] says it has not
 * finished, and releases everything else at once. On real pages that is nearly
 * every balloon, which is what keeps the page publishing as it is read.
 *
 * ## What it will not hold
 *
 * **Lettering on the artwork.** Reading order across panels is a much weaker
 * claim than reading order between balloons, and page 05's
 * `私はたった今から` really does continue — two regions later, not in the next
 * one. Holding it would join it to the wrong neighbour.
 *
 * One grouper serves one page. A page that ends mid-sentence — the rest is on
 * the next page, which this pipeline never sees — releases what it holds
 * through [flush] rather than losing it.
 */
class UtteranceGrouper(private val maxGroup: Int = DEFAULT_MAX_GROUP) {

    private val held = mutableListOf<TextRegion>()

    /**
     * Offers the next region in reading order.
     *
     * @return what is ready to be published now, or null while this region is
     *   still waiting for the one after it.
     */
    fun offer(region: TextRegion): Utterance? {
        held += region
        val waits = region.enclosure != null &&
            held.size < maxGroup &&
            JapaneseClause.isUnfinished(OcrPunctuation.normalize(region.text))
        return if (waits) null else take()
    }

    /** Whatever is still held, which is how a page ending mid-sentence ends. */
    fun flush(): Utterance? = if (held.isEmpty()) null else take()

    private fun take(): Utterance {
        val regions = held.toList()
        held.clear()
        return Utterance(
            regions = regions,
            text = regions.joinToString("") { OcrPunctuation.normalize(it.text) },
        )
    }

    companion object {
        /**
         * The most balloons one sentence may be joined across.
         *
         * Three, because a chain has to end somewhere and a misread ending in a
         * particle would otherwise swallow the rest of the page. The measured
         * splits were all two; three leaves room for one more without letting a
         * mistake run.
         */
        const val DEFAULT_MAX_GROUP = 3
    }
}

/**
 * One unit of translation: the balloons it covers, and the text to translate.
 *
 * [text] is already repaired ([OcrPunctuation]) and is what **every** region in
 * [regions] is translated as — which is what makes them agree on a cache key,
 * so a group costs one call to the provider rather than one per balloon.
 */
data class Utterance(
    val regions: List<TextRegion>,
    val text: String,
) {
    val isShared: Boolean get() = regions.size > 1
}
