# V1 — Immersive Text Translation

## Goal

Translate text exposed through Android accessibility APIs and render translated content in-place for text-heavy applications.

## In Scope

- AccessibilityService-based text acquisition
- project-owned `TextElement` normalization
- source-language auto-detection
- manual source-language selection
- target language follows system by default
- manual target-language selection
- translation provider abstraction
- translation coordination
- basic translation cache
- overlay rendering
- settings persistence
- runtime state
- capability state
- privacy exclusions
- stale-result protection
- basic diagnostics/testing seams

## Out of Scope

- OCR
- MediaProjection
- screenshot translation
- manga bubble detection
- image text removal/inpainting
- game frame analysis
- game profiles
- dialogue history/context systems
- game terminology databases

## Acceptance Criteria

V1 is complete when:

- supported text-heavy apps can expose text to the system
- visible supported text can be translated without OCR
- translated text appears near/in the original location
- scrolling does not leave stale translations behind
- changing target language produces correct new results
- translation can be paused/stopped cleanly
- sensitive input is excluded
- repeated events do not cause uncontrolled duplicate requests
- the pipeline remains responsive under ordinary reading use
