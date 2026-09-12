# System — Settings

## Goal

Persist user preferences through one settings layer.

## Core Settings

- source language mode
- target language mode
- translation/provider configuration
- runtime-related preferences where appropriate

Use DataStore for lightweight settings unless requirements justify something else.

## Rules

- UI should not own persistence details
- services should consume resolved settings/state
- avoid scattered string keys
- use project-owned settings models

Future per-app or OCR/game settings should be added only when their phases require them.
