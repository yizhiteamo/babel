package com.babel.domain.translation

import com.babel.core.model.LanguageTag
import com.babel.core.model.ProviderId
import com.babel.core.model.TranslationRequest
import com.babel.core.model.TranslationResult
import com.babel.core.model.TranslationStatus
import com.babel.domain.vision.OcrPunctuation

/**
 * Translates a line one ellipsis-separated fragment at a time.
 *
 * Comic dialogue is written in fragments — `目を…閉じて…`, `…絶対に…動かない
 * こと…` — and handing the whole string to a provider makes it guess at a
 * sentence that was never there. Measured on fifteen hand-transcribed bubbles
 * (`TranslationExperimentTest`), against the same provider:
 *
 * | Input | Whole line | By fragment |
 * |---|---|---|
 * | `……あ` | `......` — the word is gone | `…啊` |
 * | `周囲が泣いたり…店主の…言葉や誘導は無視して…` | half the sentence dropped | all of it |
 * | `…仕事中、突然視界が…増えたり…手足…色…声が変化して…` | dots throughout | reads cleanly |
 *
 * Six of the fifteen improved, one got worse, eight were unchanged, and the
 * total time went **down** — shorter strings translate faster, which more than
 * pays for the extra calls.
 *
 * Wrapping the provider rather than living inside one: this is about what any
 * engine can digest, and the engine is expected to change
 * (`docs/milestones/v2.md`). It applies to every source, and is inert for text
 * without ellipses — which is nearly all accessibility text.
 */
class FragmentingTranslator(private val delegate: Translator) : Translator {

    override val id: ProviderId = delegate.id

    override fun supports(source: LanguageTag?, target: LanguageTag): Boolean =
        delegate.supports(source, target)

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        val fragments = OcrPunctuation.fragments(request.sourceText)
        if (fragments.size <= 1) return delegate.translate(request)

        val translated = mutableListOf<String>()
        var detected: LanguageTag? = null
        var anyTranslated = false

        for (fragment in fragments) {
            // The empty strings a leading or trailing ellipsis leaves behind.
            // Kept, because they are what puts the ellipsis back in place.
            if (fragment.isBlank()) {
                translated += fragment
                continue
            }

            val result = delegate.translate(request.copy(sourceText = fragment))
            when (result.status) {
                TranslationStatus.Translated -> {
                    translated += result.translatedText
                    detected = detected ?: result.detectedSourceLanguage
                    anyTranslated = true
                }

                // Already in the target language: keep the fragment as it was,
                // so the rest of the line can still be translated around it.
                TranslationStatus.Unchanged -> translated += fragment

                // One fragment failing fails the line. Rendering a half
                // translated bubble would be worse than leaving the art alone,
                // and a failure degrades one element, never the pipeline.
                is TranslationStatus.Failed -> return result.copy(
                    originalText = request.sourceText,
                    translatedText = "",
                )
            }
        }

        return TranslationResult(
            requestId = request.requestId,
            elementId = request.elementId,
            revision = request.revision,
            originalText = request.sourceText,
            translatedText = OcrPunctuation.rejoin(translated),
            detectedSourceLanguage = detected,
            provider = id,
            // Nothing needed translating, so there is nothing to draw over the
            // original — the same judgement a provider makes for one string.
            status = if (anyTranslated) TranslationStatus.Translated else TranslationStatus.Unchanged,
        )
    }
}
