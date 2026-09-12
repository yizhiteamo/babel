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

## Provider Independence

Provider SDK/HTTP types must not escape into common domain logic.
