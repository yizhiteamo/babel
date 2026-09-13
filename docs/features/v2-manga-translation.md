# V2 — Immersive Manga Translation

## Goal

Translate mostly static visual content such as manga and comics while preserving the reading experience.

## In Scope

- screen frame acquisition (accessibility screenshots — ADR 009 replaced MediaProjection)
- OCR
- OCR output normalized to `TextElement`
- vertical Japanese text support
- page/region change detection
- text-region detection
- speech-bubble-aware layout where useful
- original-text masking/removal
- translated text re-layout
- reuse of V1 language, translation, cache, settings, state, and rendering foundations

## Out of Scope

- continuous game frame analysis
- gameplay subtitle tracking
- game-specific profiles
- game terminology systems
- thermal-aware real-time scheduling

## Acceptance Criteria

V2 is complete when a mostly static comic page can be detected, OCR-processed, translated, and re-rendered without rebuilding the V1 translation core.
