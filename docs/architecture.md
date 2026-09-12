# Architecture

## Objective

The architecture must allow different text-acquisition methods to enter the same translation and rendering pipeline.

The main design goal is to avoid rewriting downstream systems when new acquisition methods are added.

## High-Level Flow

```text
Text Source
   |
   v
Acquisition Adapter
   |
   v
TextElement
   |
   v
Language Resolution
   |
   v
TranslationCoordinator
   |\
   | +--> TranslationCache
   |
   +----> Translator
   |
   v
TranslationResult
   |
   v
RenderedTranslation
   |
   v
Renderer
```

## Layers

### Platform Layer

Owns Android-specific capabilities and converts platform objects into project-owned models.

### Domain Layer

Owns text models, language models, translation requests/results, coordination, cache policy, and runtime state.

### Data/Provider Layer

Owns provider implementations, persistence, provider networking, and provider-specific mapping.

### UI Layer

Jetpack Compose owns the app's own screens and observes application state.

## Stable Contracts

Establish early:

- `TextElement`
- source/target language modes
- `TranslationRequest`
- `TranslationResult`
- `Translator`
- `TranslationCoordinator`
- `RenderedTranslation`
- runtime state
- capability state

## Concurrency

- provider calls must not block the main thread
- translation work must be cancellable
- obsolete results must be rejected
- duplicate events should be coalesced where appropriate
- latest relevant visible content wins

## Error Boundaries

Classify errors instead of collapsing everything into one failure state.

A provider failure must not crash acquisition or UI.

## Performance Principle

Optimize the active phase for its real workload.

Do not import future-phase complexity solely for hypothetical performance needs.

## Data Ownership

Distinguish transient source text, cache data, persistent settings, and diagnostic metadata.

Raw source text should remain transient by default.
