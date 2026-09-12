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
    val metadata: Map<String, String> = emptyMap(),
)
