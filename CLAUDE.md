# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Purpose

This project is an Android immersive translation application.

Its goal is to translate content displayed by other Android apps and render the translation in-place with minimal disruption to the original reading, comic, or game experience.

The project evolves through multiple product phases, but this file intentionally does not track the active version or milestone.

See `docs/roadmap.md` and `docs/milestones/` for current development status.

---

## Technology Direction

Primary stack:

- Kotlin
- Android native APIs
- Jetpack Compose for the application's own UI
- Kotlin Coroutines / Flow for asynchronous state and pipelines
- DataStore for lightweight persistent settings
- Room only when structured persistent data requires it

Platform-specific capabilities should be introduced only when required by the active feature scope.

---

## Core Architectural Principles

The translation pipeline must not depend directly on how text was acquired.

All text sources must be normalized into project-owned models before entering the common translation pipeline.

Conceptual flow:

`Text Source -> TextElement -> TranslationRequest -> TranslationResult -> RenderedTranslation -> Renderer`

Known source examples:

- Accessibility text
- OCR text
- Tracked OCR/game text

Downstream systems must not depend on source-specific platform types.

Future compatibility should be achieved through stable boundaries and project-owned models, not by pre-implementing future runtime systems.

---

## Module Boundaries

Keep acquisition, translation, persistence, rendering, and UI separated.

Rules:

- Compose UI must not contain AccessibilityService or screen-capture logic.
- Acquisition layers must not directly call translation provider APIs.
- Renderers must not directly call translation providers.
- Translation providers must not know about Android overlay windows or Compose UI.
- Translation cache must be accessed through the translation layer, not directly from UI.
- Platform-specific objects must be converted into project-owned models at module boundaries.
- Avoid global mutable state.
- Prefer immutable state and explicit state transitions.
- Prefer coroutines and Flow for asynchronous pipelines.
- Long-running work must be cancellable.
- Old asynchronous results must not overwrite newer visible content.

See `docs/architecture.md`.

---

## Language Rules

Do not assume Chinese is always the target language.

Default behavior:

- Source language: automatic detection
- Target language: follow system language

Users may manually override both source and target languages.

App UI language and translation target language are separate concepts.

See `docs/systems/language.md`.

---

## Scope Discipline

Implement only the scope required by the active milestone.

Before implementing functionality, consult:

- `docs/roadmap.md`
- the relevant file under `docs/features/`
- the active milestone under `docs/milestones/`
- relevant system documentation under `docs/systems/`

Do not implement functionality assigned to a future phase unless explicitly requested.

Future phases may influence:

- interface boundaries
- project-owned data models
- extension points
- module separation

Future phases must not justify prematurely implementing:

- unused runtime systems
- unused dependencies
- unused services
- speculative abstractions
- placeholder subsystems with no current consumer

Prefer the smallest architecture that supports the active phase without blocking known future phases.

---

## Documentation Routing

Read only documentation relevant to the task.

Use:

- Product version definitions: `docs/features/`
- Overall current state: `docs/roadmap.md`
- Detailed active progress: `docs/milestones/`
- Architecture: `docs/architecture.md`
- Technical systems: `docs/systems/`
- Architectural rationale: `docs/decisions/`

Common system documents:

- Language: `docs/systems/language.md`
- Text model: `docs/systems/text-model.md`
- Translation: `docs/systems/translation.md`
- Accessibility: `docs/systems/accessibility.md`
- Overlay rendering: `docs/systems/overlay.md`
- Cache: `docs/systems/cache.md`
- Settings: `docs/systems/settings.md`
- Runtime state: `docs/systems/runtime-state.md`
- Capabilities: `docs/systems/capabilities.md`
- Privacy: `docs/systems/privacy.md`
- Translation scope: `docs/systems/scope.md`
- Testing: `docs/systems/testing.md`

Do not load all documentation unless the task genuinely spans all systems.

---

## Documentation Policy

`CLAUDE.md` is a stable project constitution and navigation file.

Do not store in this file:

- active version
- milestone progress
- daily progress
- bug history
- implementation diary
- commit history
- temporary task notes
- percentage complete

Use:

- `docs/roadmap.md` for version-level current state
- `docs/milestones/` for detailed current progress
- `docs/features/` for stable version scope and acceptance criteria
- Git history for what changed at code level
- ADR files for durable architectural decisions

When documentation becomes large:

- split by system or feature
- keep this file concise
- link instead of duplicating
- update existing truth instead of appending chronological notes

---

## Data and Privacy Rules

Never translate or log sensitive fields that should be excluded, including password-like or explicitly protected input.

Avoid persisting raw screen text unless required by a documented feature.

Diagnostic logs must not contain full user-visible screen text by default.

Translation providers may receive text only through the translation layer, where privacy policy and provider configuration can be applied consistently.

See `docs/systems/privacy.md`.

---

## Implementation Quality

When adding or changing functionality:

1. Identify the responsible feature and system documents.
2. Read only the relevant documentation.
3. Preserve module boundaries.
4. Prefer extending project-owned models over leaking platform models downstream.
5. Add or update tests around behavior and state transitions.
6. Update documentation only when intended behavior or architecture changes.
7. Update milestone state only when acceptance conditions are actually satisfied.
8. Do not mark a roadmap phase complete unless its feature acceptance criteria are satisfied.

When a requested change conflicts with an accepted ADR, do not silently bypass it. Update or supersede the ADR explicitly.

---

## Build and test

The system `java` on PATH is JDK 8, and `JAVA_HOME` points at it. Gradle is pinned to JDK 17 through `org.gradle.java.home` in `~/.gradle/gradle.properties` — deliberately outside the repository, since the path is machine-specific. Always go through the wrapper: `./gradlew`, never a `gradle` command.

```bash
./gradlew assembleDebug                # build the APK
./gradlew build                        # everything, including lint + unit tests
./gradlew test                         # all JVM unit tests
./gradlew :domain:test                 # one pure-Kotlin module's tests
./gradlew :data:translation:testDebugUnitTest   # an Android module (plain `test` runs both variants)
./gradlew :domain:test --tests "com.babel.domain.translation.CoordinatorTest"        # one class
./gradlew :domain:test --tests "com.babel.domain.translation.CoordinatorTest.rejects*" # one method
./gradlew connectedAndroidTest         # instrumentation tests (needs a device/emulator)
./gradlew installDebug                 # install on the connected device
```

The configuration cache is enabled. Build scripts must stay configuration-cache compatible — no reading `project` at execution time in custom tasks.

`local.properties` holds the local `sdk.dir` and is not committed. Note that Gradle does not read its own properties from `local.properties` — that file belongs to the Android Gradle Plugin (`sdk.dir`, `ndk.dir`) only. Versions live in `gradle/libs.versions.toml`, including `compileSdk`/`targetSdk`/`minSdk`, which module scripts read via `libs.versions.*.get().toInt()`.

---

## Module layout

The conceptual flow above is split so that each Module Boundaries rule is a compile error rather than a convention:

| Module | Layer | Owns |
|---|---|---|
| `:core:model` | domain | `TextElement`, `TextBounds`, language modes, `TranslationRequest`/`Result`, `RenderedTranslation`, runtime state, capability state |
| `:core:common` | domain | `DispatcherProvider`, `BabelLogger`, `Redact` |
| `:domain` | domain | Pipeline contracts plus `DefaultLanguageResolver`, `DefaultSensitiveContentPolicy`, `DefaultTranslationScopePolicy`, `DefaultTranslationCoordinator` |
| `:core:testing` | test | Shared fakes (`FakeTranslator`, `RecordingRenderer`, …). Consumed via `testImplementation` only |
| `:data:settings` | data | `DataStoreSettingsRepository`, `AndroidSystemLocaleProvider` |
| `:data:translation` | data | `MlKitTranslator` (on-device), `InMemoryTranslationCache` |
| `:platform:screen` | platform | `ScreenFrameSource` — contract only, no implementation |
| `:platform:accessibility` | platform | `AccessibilityService`, node → `TextElement` normalization, screen frames (ADR 009) |
| `:platform:overlay` | platform | overlay windows, `TranslationRenderer`, coordinate mapping |
| `:platform:capture` | platform | OCR recognition, region grouping → `TextElement` (V2) |
| `:app` | UI | Compose screens, `AndroidLogger`, Hilt wiring for the pure-Kotlin domain |

Dependency rules that the build enforces:

- `:core:model`, `:core:common`, and `:domain` use the **Kotlin JVM** plugin, not the Android plugin. An `android.*` import in the domain does not compile. Bounds are `TextBounds`, not `android.graphics.Rect`; locales are `LanguageTag`, not `java.util.Locale` leaking outward.
- `:platform:*` and `:data:*` depend on `:domain` only — never on each other. An acquisition adapter cannot call a provider, and a renderer cannot call a provider, because neither can see `:data:translation`.
- The one exception is `:platform:screen`, which any platform module may depend on. It holds contracts and no implementation, and exists only because `ScreenFrameSource` must mention `Bitmap`, which cannot enter the pure-Kotlin domain. Two platform modules depending on it is not the same as depending on each other: the accessibility service takes the frames and the OCR module reads them, and neither can see the other — which is what keeps ML Kit out of the V1 path.
- `:app` is the only module that depends on every layer; that is its job (DI wiring). Acquisition and capture logic must not appear there.

Adding a new acquisition method (OCR in V2, tracked OCR in V3) means a new `:platform:*` module implementing `TextSource` plus a new `TextSourceType` value — nothing downstream changes. That is the point of the split; do not break it for convenience.

---

## Contracts already established

Every stable contract in `docs/architecture.md` exists, and the domain side is implemented: language resolution, privacy exclusion, the coordinator, the cache, and an on-device provider. `:platform:*` is still empty — acquisition and rendering are the remaining V1 work, and each corresponds to an unchecked item in `docs/milestones/v1.md`. Implement against the existing contracts; change a contract only when the system doc that defines it changes too.

`DefaultTranslationCoordinator` mutates tracked state **only from its mailbox coroutine**. Translations run concurrently and report back as messages. Keep it that way: touching the tracked map from a translation coroutine reintroduces a race between the revision check and the events that bump revisions.

Behavioral invariants that must survive any change:

- `Revision` on `TextElement`, `TranslationRequest`, `TranslationResult`, and `RenderedTranslation` exists so a late result can be dropped. Compare it before rendering.
- `Translator.translate` returns a `TranslationResult` with a `Failed` status; it does not throw. A provider failure degrades one element, not the pipeline.
- `TranslationCacheKey` includes target language and provider. Never key on source text alone.
- `TextSourceEvent.Removed`/`Cleared` are how stale overlays get cleaned up during scrolling and app switches.
- Pass `Redact.text(...)` to loggers, never raw screen text.
- `LanguageResolver` owns locale policy, including narrowing a device tag to what a provider accepts. ML Kit rejects `zh-Hans-CN` outright, so nothing downstream may assume a regional tag survives.
- Manga mode needs API 30 (`AccessibilityService.takeScreenshot`) and reports `CaptureState.UNAVAILABLE` below it. V1 still runs down to minSdk 26 — only manga mode is gated (ADR 009).
- A frame includes Babel's own overlays, so `FrameChangeDetector` is what stops the OCR path from reading its own output. `FLAG_SECURE` is not an alternative: measured on device, it blanks the entire mirror and the user's screenshots with it.
- Scope and privacy are separate policies and must stay that way: scope asks whether an app is worth translating, privacy whether text may leave the screen. Adding an app to one does not belong in the other.

Testing on a device: `uiautomator dump` disconnects the accessibility service while it runs, which tears down the pipeline and resets manga mode. Read state from `logcat` and `screencap` instead — a UI dump taken to check a result is what destroys it.

Testing the pipeline: a collector of `renderUpdates` must run on `UnconfinedTestDispatcher`. A `StandardTestDispatcher` collector in `backgroundScope` is never resumed by `advanceUntilIdle`, and render assertions then pass against an empty renderer instead of failing.

---

## Conventions

- Kotlin sources live under `src/main/kotlin/`.
- Package root is `com.babel`; `applicationId` is `com.babel` (placeholder — change before any release).
- DI is Hilt + KSP. Pure-Kotlin modules use `javax.inject` annotations only; the Hilt plugin is applied in Android modules.

---

## 提交节奏

以 `docs/milestones/v1.md` 中的单个条目为提交单位。提交前必须 `./gradlew build` 通过。

格式：`<type>(<scope>): <简述>`，如 `feat(domain): 实现 TranslationCoordinator`。
type 取 feat / fix / refactor / test / docs / chore；scope 取模块名（domain、data、platform、app）。

勾选里程碑条目与对应实现放在同一个 commit —— 条目只有在 `docs/features/` 的验收条件真正满足时才能勾选。
