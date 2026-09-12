# V3 — Immersive Game Translation

## Goal

Provide low-latency translation for changing game content such as dialogue, subtitles, menus, and selected UI regions.

## In Scope

- frame/region change detection
- region tracking
- incremental OCR
- text stability detection
- cancellation of obsolete work
- low-latency rendering
- context-aware translation where useful
- optional terminology and game profiles
- performance scheduling/throttling
- reuse of common translation architecture

## Out of Scope

Only explicitly excluded future capabilities should be listed when V3 planning becomes active.

## Acceptance Criteria

V3 is complete when relevant game text can be translated with acceptable latency and resource usage without creating a separate game-only translation engine.
