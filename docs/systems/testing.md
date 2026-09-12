# System — Testing

## Goal

Keep core translation behavior testable without requiring a live third-party app for every test.

## Test Seams

Provide fakes/mocks for:

- Translator
- TranslationCache
- language resolution inputs
- text-source adapters where practical
- renderer where practical

## Core Cases

Test:

- language resolution
- cache key separation
- stale-result rejection
- duplicate-event handling
- runtime-state transitions
- capability handling
- sensitive-content exclusion
- stop/cleanup behavior

Use Android device/instrumentation tests for platform integration such as accessibility and overlay behavior.
