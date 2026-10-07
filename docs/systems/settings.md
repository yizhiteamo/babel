# System — Settings

## Goal

Persist user preferences through one settings layer.

## Core Settings

- source language mode
- target language mode
- translation/provider configuration
- runtime-related preferences where appropriate: whether text translation was
  left paused, whether manga mode was left on, and what the quick-settings tile
  took down (`switchedOff`), so turning it on puts back exactly that

Use DataStore for lightweight settings unless requirements justify something else.

## Rules

- UI should not own persistence details
- services should consume resolved settings/state
- avoid scattered string keys
- use project-owned settings models
- only the user's own actions write the runtime preferences — the home screen's
  buttons and the tile. A restore after a process death reads them and writes
  nothing, or it would erase the choice it is restoring

Future per-app or OCR/game settings should be added only when their phases require them.
