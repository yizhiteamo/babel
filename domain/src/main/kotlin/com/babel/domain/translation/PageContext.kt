package com.babel.domain.translation

import com.babel.core.model.Revision

/**
 * What a page has said so far, offered to the provider as context.
 *
 * A balloon translated on its own is translated without a speaker, without the
 * line before it, and without knowing what the page has already called anyone.
 * Two measured consequences, both on `kr-mag-01`: 「선생님, 줄게!」 came back as
 * 「给你徐世妮」 — a title read as a personal name, which in isolation it could
 * be — and 「이부키」 came out as 伊布卡, 伊吹 and 伊布吉 on the same page.
 *
 * Neither is a weak model. Both are a model asked a question with the answer
 * left out of it.
 *
 * ## Only what has already happened
 *
 * Publishing is incremental — a balloon reaches the screen before the next one
 * is read, and that is a measured latency win that is not being given back. So
 * the first balloon of a page has no context and the last has the most. Name
 * consistency survives that ordering (the first rendering sets it and the rest
 * follow); a title in the very first balloon does not. **That is a limit of the
 * design, not an oversight.**
 *
 * ## Bounded on purpose
 *
 * A page runs to twenty-five balloons. Sending all of them with every request
 * would grow the body with the page, so the source lines are capped at the most
 * recent [MAX_LINES] and the whole block at [MAX_CHARS]. Agreed renderings are
 * kept ahead of raw lines when the budget runs out: they are the part that
 * makes a page read as one translation rather than several.
 */
class PageContext {

    private var page: Revision? = null
    private val seen = ArrayDeque<String>()
    private val agreed = LinkedHashMap<String, String>()

    /** Notes a line this page contains, starting a new page when it turns over. */
    fun note(revision: Revision, text: String) {
        if (page != revision) {
            page = revision
            seen.clear()
            agreed.clear()
        }
        if (text.isBlank()) return
        seen.remove(text)
        seen.addLast(text)
        while (seen.size > MAX_LINES) seen.removeFirst()
    }

    /** Records what this page settled on for [source]. */
    fun agree(source: String, translated: String) {
        if (source.isBlank() || translated.isBlank()) return
        agreed[source] = translated
        while (agreed.size > MAX_LINES) {
            agreed.remove(agreed.keys.first())
        }
    }

    /**
     * The block to send alongside [exclude], or null when there is nothing to
     * say that the provider does not already have.
     *
     * [exclude] is the line being translated; repeating it as its own context
     * would only invite the model to answer twice.
     */
    fun forLine(exclude: String): String? {
        val renderings = agreed.entries
            .filter { it.key != exclude }
            .map { "${it.key} => ${it.value}" }
        val lines = seen.filter { it != exclude && it !in agreed }
        if (renderings.isEmpty() && lines.isEmpty()) return null

        val text = StringBuilder()
        if (renderings.isNotEmpty()) {
            text.append("Already rendered on this page:\n")
            renderings.forEach { text.append("- ").append(it).append('\n') }
        }
        if (lines.isNotEmpty() && text.length < MAX_CHARS) {
            text.append("Other lines on this page:\n")
            for (line in lines.reversed()) {
                if (text.length + line.length > MAX_CHARS) break
                text.append("- ").append(line).append('\n')
            }
        }
        return text.toString().trimEnd().takeIf { it.isNotBlank() }
    }

    private companion object {
        /** Enough for a page's worth of short balloons, short of a long one. */
        const val MAX_LINES = 20

        /** A ceiling on the block as a whole, whatever the line count. */
        const val MAX_CHARS = 1_200
    }
}
