package com.babel.core.model

/** Stable identity for one piece of visible text while it is on screen. */
@JvmInline
value class TextElementId(val value: String)

/**
 * Monotonic generation counter used to reject stale work.
 *
 * A result carrying an older revision than the element currently on screen must
 * never be rendered. See `docs/systems/translation.md`.
 */
@JvmInline
value class Revision(val value: Long) : Comparable<Revision> {
    override fun compareTo(other: Revision): Int = value.compareTo(other.value)

    fun next(): Revision = Revision(value + 1)
}

/** Where a piece of text came from. New acquisition methods add values here. */
enum class TextSourceType {
    ACCESSIBILITY,

    /** Recognised from captured pixels (V2). */
    OCR,
}

/** Identity of the app/window the text belongs to, when it is known. */
data class SourceIdentity(
    val packageName: String? = null,
    val windowId: Int? = null,
)

/**
 * Project-owned representation of visible text, shared by every acquisition
 * method (ADR 004). No Android or provider type may appear in this model.
 */
data class TextElement(
    val id: TextElementId,
    val text: String,
    val bounds: TextBounds,
    val sourceType: TextSourceType,
    val source: SourceIdentity = SourceIdentity(),
    val revision: Revision = Revision(0),
    /**
     * Set by the acquisition layer for password-like or otherwise protected
     * input (e.g. `AccessibilityNodeInfo.isPassword`). Such text must never
     * reach a provider, a cache, or a log — see `docs/systems/privacy.md`.
     */
    val isProtected: Boolean = false,
    /**
     * How the source looked, where the acquisition method could see it. Empty
     * from accessibility, which reports no colours; populated by OCR, which
     * holds the pixels.
     */
    val style: SourceStyle = SourceStyle.UNKNOWN,
    /**
     * The language this text is in, where the acquisition layer knows rather
     * than guesses.
     *
     * OCR does know: manga mode reads with a Japanese recogniser, so anything
     * it returns is Japanese. Detecting the language from that text instead is
     * measurably unreliable — on one real page, four translations were
     * attributed to Japanese once, English twice and Finnish once
     * (`docs/milestones/v2.md`). Accessibility leaves this null: a node carries
     * no such claim.
     *
     * A manual override by the user still wins; this only replaces guessing.
     */
    val sourceLanguage: LanguageTag? = null,
    /**
     * Set when this element shares one translation with its neighbours.
     *
     * A sentence cut across several balloons has to be translated whole or it
     * is destroyed, so the acquisition layer gives every balloon in such a
     * group the **same** [text] — the joined line — and marks each with its
     * place in the group. The domain then shows each one its own part
     * (`SharedTranslation`).
     *
     * Same text means the cache answers every balloon after the first, so a
     * group of three still costs one call to the provider.
     *
     * Null everywhere else, including the whole accessibility path: a label is
     * never half of its neighbour.
     */
    val share: TextShare? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * One element's place in a group that shares a translation.
 *
 * Carries the group's weights rather than a group id so that nothing has to
 * hold the group together: an element knows its own share, and the division is
 * a pure function of what it already has. The weights are the balloons' areas
 * in reading order, which is what decides how much of the line each one takes.
 */
data class TextShare(
    val index: Int,
    val weights: List<Int>,
) {
    val count: Int get() = weights.size
}
