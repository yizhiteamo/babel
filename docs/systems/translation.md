# System — Translation

## Goal

Provide one translation pipeline shared by all text sources.

## TranslationRequest

Conceptual fields:

```text
TranslationRequest
- requestId
- elementId
- sourceText
- sourceLanguage
- targetLanguage
- context (optional)
- metadata (optional)
```

## TranslationResult

Conceptual fields:

```text
TranslationResult
- requestId
- elementId
- originalText
- translatedText
- detectedSourceLanguage (optional)
- provider
- status
```

## Translator

Use a provider-neutral interface.

## TranslationCoordinator

Responsibilities:

- receive normalized text
- resolve language configuration
- check cache
- invoke provider if required
- cancel/ignore stale work
- publish valid results
- record cache entries
- surface errors safely

## Stale Result Protection

Old results must never overwrite newer visible content.

## Local and remote

Two routes, chosen by settings and defaulting to the on-device one
(`RoutingTranslator`). Remote translation runs only when the user has both
selected it and configured an endpoint — see ADR 010 for why it exists at all
and `docs/systems/privacy.md` for what it changes.

The routes are not wrapped identically, and that is deliberate.
`FragmentingTranslator` splits a line at its ellipses, which measurably recovers
sentences ML Kit would otherwise drop; measured against a stronger model the
same splitting *hurts*, because that model can use the context a whole balloon
gives it (`docs/milestones/v2.md`). So the wrapper belongs to the engine it
compensates for, not to the pipeline.

## Provider Independence

Provider SDK/HTTP types must not escape into common domain logic.
