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
- sourceType
- conversational
```

## Game translations (V3)

A game's text is drawn in one of three ways, chosen per line:

- **Erased and redrawn** (`ErasedTextView`) — the line has a `SourceStyle.backdrop`
  (`RowGradient`): the panel is painted back row by row from the colours at
  either end, and the translation is set over it in the original's ink, height,
  weight and left edge, with an outline where the original has one or where
  the ink alone would fall under 4.5:1 contrast with the panel.
- **Boxed** (`TranslationTextView`) — no backdrop: the line sits on artwork the
  interpolation cannot paint back.
- **Not drawn** — the game's own controls in another script than the game
  (`GameLayout.controls`: single Latin words such as AUTO, MENU, Touch, and a
  player's typed name, in a game written in another script).

Touches:

- **Dialogue** (`conversational`) is a touchable window. A short tap is handed
  to the game; a **long press released before 1 s switches the whole screen**
  between translation and original; **held past 1 s, the press goes on to the
  game** as a held press (`TapForwarder.hold`) until the finger lifts. The original state lasts until the next line appears, or
  another long press on the same spot; a small bar in the line's ink marks
  where a translation is standing aside.
- **Labels** (buttons, menu entries, names) are trusted
  `TYPE_ACCESSIBILITY_OVERLAY` windows that let every touch through — opaque,
  yet the button under them works untouched, holds and drags included.

## Coordinate Mapping

Centralize coordinate conversion. Do not scatter correction logic across rendering code.

## Cleanup

Remove overlays when source content disappears, translation stops, windows/apps change, or visible translations become invalid.
