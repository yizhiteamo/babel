# System — Overlay Rendering

## Goal

Render translated text near the original content without blocking ordinary interaction.

## Responsibilities

- own overlay lifecycle
- render `RenderedTranslation`
- map source bounds to render coordinates
- pass touches through when appropriate
- update moved content
- remove stale content
- handle configuration changes

## RenderedTranslation

Conceptual fields:

```text
RenderedTranslation
- elementId
- text
- bounds
- revision
- styleHints
```

## Coordinate Mapping

Centralize coordinate conversion. Do not scatter correction logic across rendering code.

## Cleanup

Remove overlays when source content disappears, translation stops, windows/apps change, or visible translations become invalid.
