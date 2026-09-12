# System — Privacy and Data Handling

## Goal

Minimize unnecessary handling and persistence of user-visible screen content.

## Principles

- process only content required for translation
- exclude password-like/protected input
- avoid persisting raw screen text by default
- avoid full source text in normal logs
- route provider access through the translation layer
- make local vs remote processing boundaries explicit

Captured images and OCR artifacts require a renewed privacy review when visual translation phases become active.
