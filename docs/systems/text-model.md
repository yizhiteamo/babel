# System — Text Model

## Goal

Provide a project-owned representation of visible text shared by all acquisition methods.

## TextElement

Conceptual fields:

```text
TextElement
- id
- text
- bounds
- sourceType
- sourceApp/package identity when relevant
- revision/generation
- metadata
```

The model must not expose source-specific platform types.

## Identity

Identity must support movement, removal, deduplication, and stale-result rejection during the lifetime of visible content.

## Bounds

Coordinate spaces must be explicit. Source bounds and rendering coordinates must not be assumed identical.

## Source Type

Examples:

- Accessibility
- OCR
- TrackedOCR

## Sensitive Content

Sensitive/protected content should be excluded before entering normal translation flow.
