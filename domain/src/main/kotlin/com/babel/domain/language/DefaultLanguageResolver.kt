package com.babel.domain.language

import com.babel.core.model.LanguagePair
import com.babel.core.model.LanguageTag
import com.babel.core.model.SourceLanguageMode
import com.babel.core.model.TargetLanguageMode
import java.util.Locale

/**
 * The single owner of locale policy (ADR 003).
 *
 * [supported] is supplied by whoever knows the active provider's capabilities —
 * the domain must not hardcode a provider's language list.
 *
 * [fallbackTarget] is used only when the system language is not translatable at
 * all; without it, a user on an unsupported device locale would get a pipeline
 * that silently produces nothing.
 */
class DefaultLanguageResolver(
    private val systemLocaleProvider: SystemLocaleProvider,
    supported: Collection<LanguageTag>,
    private val fallbackTarget: LanguageTag = LanguageTag("en"),
) : LanguageResolver {

    /** Normalized once, so lookups do not re-normalize on every resolve. */
    private val supportedTags: Set<LanguageTag> = supported.map(::normalize).toSet()

    private val supportedByBaseLanguage: Map<String, LanguageTag> =
        supportedTags.associateBy { baseLanguageOf(it) }

    override fun resolve(
        source: SourceLanguageMode,
        target: TargetLanguageMode,
    ): LanguagePair {
        val resolvedSource = when (source) {
            SourceLanguageMode.AutoDetect -> null
            is SourceLanguageMode.Manual -> matchSupported(source.language)
        }

        val resolvedTarget = when (target) {
            TargetLanguageMode.FollowSystem -> matchSupported(systemLocaleProvider.current())
            is TargetLanguageMode.Manual -> matchSupported(target.language)
        } ?: normalize(fallbackTarget)

        return LanguagePair(source = resolvedSource, target = resolvedTarget)
    }

    /**
     * Canonical BCP-47 form. Accepts the underscore form (`zh_CN`) that Android
     * and Java APIs still hand out in places, since a tag that fails to parse
     * would otherwise silently become `und`.
     */
    override fun normalize(tag: LanguageTag): LanguageTag {
        val raw = tag.value.trim().replace('_', '-')
        if (raw.isEmpty()) return tag

        val locale = Locale.forLanguageTag(raw)
        val canonical = locale.toLanguageTag()
        // `und` means the tag was unparseable; keep the original rather than
        // pretending it resolved to something.
        return if (canonical == UNDETERMINED) tag else LanguageTag(canonical)
    }

    override fun isSupported(tag: LanguageTag): Boolean = matchSupported(tag) != null

    override fun supportedLanguages(): List<LanguageTag> =
        supportedTags.sortedBy { it.value }

    /**
     * Exact match first, then the base language. A device reporting
     * `zh-Hans-CN` must still resolve against a provider that only lists `zh`,
     * otherwise `FollowSystem` would fall back for most Chinese users.
     */
    private fun matchSupported(tag: LanguageTag): LanguageTag? {
        val normalized = normalize(tag)
        if (normalized in supportedTags) return normalized
        return supportedByBaseLanguage[baseLanguageOf(normalized)]
    }

    private fun baseLanguageOf(tag: LanguageTag): String =
        Locale.forLanguageTag(tag.value).language.ifEmpty { tag.value.substringBefore('-') }

    private companion object {
        const val UNDETERMINED = "und"
    }
}
