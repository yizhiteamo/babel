package com.babel.platform.capture

/**
 * Scoring shared by the manga measurements, so the two tests cannot drift into
 * disagreeing about what "accurate" means.
 *
 * The measure is edit distance against the bubble a region most resembles, not
 * character overlap across the page. The page-wide measure was used first and
 * flattered badly: it asks only whether a character appears *somewhere*, so a
 * region holding two bubbles spliced together scored well while translating to
 * nonsense.
 */
internal object BubbleScoring {

    /**
     * Hand-transcribed bubble text for the pages used to judge accuracy,
     * speech bubbles only.
     *
     * Sound effects drawn onto the art are deliberately absent: they are out of
     * V2 scope, so a page should not be marked down for text the design intends
     * to leave alone.
     *
     * **This is one person's reading of the pages, so it is itself a source of
     * error.** A character transcribed wrongly here counts against OCR that
     * read it rightly.
     */
    val groundTruth: Map<String, List<String>> = mapOf(
        "jap-mag-01.jpg" to listOf(
            "先生も汗拭きシート使いますか",
            "いいの",
            "はいいくらでも使ってください",
            "そっちは私の使用済み",
            "先生先生",
        ),
        "jap-mag-04.jpg" to listOf(
            "スーパーアルバイターの資格次が最終試験この本も最終ですッ",
            "どんなことが書かれて",
            "仕事中突然視界が高くなったり増えたり手足色声が変化して",
            "周囲が泣いたり騒いだり逃げ出した時店主の言葉や誘導は無視して",
            "目を閉じて",
            "絶対に動かないこと",
            "あんまりわかんないケドッ",
            "がんばりまーす",
        ),
    )

    /**
     * How well a set of recognised regions covers a page's bubbles, 0..1.
     *
     * Scored per **expected bubble** rather than per region: a page that splits
     * one bubble in two should not be rewarded for the halves matching
     * something. Each bubble takes its best region and they are averaged.
     */
    fun pageScore(expected: List<String>, regions: List<String>): Double {
        if (expected.isEmpty()) return 1.0
        val cleaned = regions.map { it.filterNot(Char::isWhitespace) }
        return expected.map { bubble -> cleaned.maxOfOrNull { similarity(it, bubble) } ?: 0.0 }
            .average()
    }

    /**
     * 1.0 for identical, falling with each edit needed — comparing only the
     * characters that carry meaning.
     *
     * Punctuation is stripped from **both** sides first. The transcriptions
     * above omit it while the recogniser returns it, so a bubble read perfectly
     * scored 66% purely because of two question marks. Measuring against a
     * yardstick that penalises correct output would have sent the next round of
     * work after the wrong problem.
     *
     * `ー` is deliberately not stripped: it is a long vowel inside words like
     * スーパー, not punctuation, and removing it would corrupt what is being
     * compared.
     */
    fun similarity(got: String, expected: String): Double {
        val a = got.meaningful()
        val b = expected.meaningful()
        val longest = maxOf(a.length, b.length, 1)
        return 1.0 - distance(a, b).toDouble() / longest
    }

    private fun String.meaningful(): String = filterNot { it.isWhitespace() || it in NOISE }

    private val NOISE = setOf(
        '?', '？', '!', '！', '.', '。', ',', '、', '…', '‥', '・', '．', '，',
        ':', '：', ';', '；', '「', '」', '『', '』', '(', ')', '（', '）',
        '[', ']', '【', '】', '"', '\'', '　',
    )

    /** Levenshtein, so a spliced pair of bubbles scores far worse than a typo. */
    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    fun percent(value: Double): String = "${(value * 100).toInt()}%"
}
