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
- source traits (`recognised`, `carriesContext`, `conversational`, `durableLabel`, `keepsLatinWords`, `pausable`)
- metadata
```

The model must not expose source-specific platform types.

## Source Traits

Downstream translation stages must not branch on `TextSourceType` to decide how
an element is translated, cached, or paused. Instead, each acquisition adapter
describes its normalised output on `TextElement`:

- `recognised`: `text` was read from pixels by OCR rather than copied from a UI
  node, so a provider may account for recognition mistakes (set by manga OCR
  and game mode).
- `carriesContext`: elements from this source participate in `PageContext` —
  `conversational` lines receive surrounding lines as context, and settled
  translations are recorded for subsequent lines (set by manga OCR and game
  mode).
- `conversational`: this element is dialogue (a speech balloon or a spoken game
  line) rather than a caption or interface label.
- `durableLabel`: a short interface word or character name whose translation can
  be resolved in reusable word/bracket parts and persisted via
  `TranslationCache.putDurable` (set by game mode for labels).
- `keepsLatinWords`: Latin-alphabet words embedded in non-Latin text (such as a
  player's handle) should be masked before calling a provider and restored into
  the result (`KeptWordsTranslator`, carried onto `TranslationRequest`; set by
  game mode).
- `pausable`: `TranslationCoordinator.pause` holds back new elements from this
  source (set by accessibility text; false for modes with their own on/off
  switches).

`TextSourceType` remains on `TextElement` and `TextSourceEvent.Cleared` to
identify which source is clearing its own output and how a renderer hosts its
overlay window.

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
