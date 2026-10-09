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

## Style

`TextElement.style` (`SourceStyle`) carries what the acquisition method could
see of how the text looked, all optional: background and ink colours,
orientation, glyph size, and — game mode only — `backdrop` (a `RowGradient`:
the panel's colour at either end of each pixel row, for erasing the line),
`outlineColor` and `strokeWeight`. Null always means "not known"; a renderer
falls back, it never paints nothing. Colours are packed ARGB ints so the
model stays free of Android types.

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
