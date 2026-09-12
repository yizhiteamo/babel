# Babel

An Android immersive translation app. It translates text shown by *other* apps and renders the
translation in place, so reading, comics, and games stay readable rather than becoming a
copy-paste chore.

## Status

V1 in progress. The translation pipeline works end to end in tests: language resolution, privacy
exclusion, coordination with stale-result rejection, caching, and on-device translation via ML Kit.

Not yet built: accessibility text acquisition and overlay rendering — the two pieces that need a
real device.

See `docs/roadmap.md` for version-level state and `docs/milestones/` for detail.

## Phases

| Version | Scope |
|---|---|
| V1 | Immersive text translation via Android accessibility APIs (no OCR) |
| V2 | Immersive manga translation |
| V3 | Immersive game translation |

Each phase is defined in `docs/features/`.

## Building

Requires JDK 17 and the Android SDK (`compileSdk` 35, `minSdk` 26).

```bash
./gradlew assembleDebug
./gradlew test
```

Create `local.properties` with `sdk.dir=` pointing at your Android SDK.

The build does not pin a JDK path, since that is machine-specific. If Gradle picks up a JDK older
than 17, either point `JAVA_HOME` at a JDK 17, or set `org.gradle.java.home` in your own
`~/.gradle/gradle.properties` — not in the project's `gradle.properties`. Note that Gradle does not
read its own properties from `local.properties`; that file is the Android Gradle Plugin's and holds
`sdk.dir` only.

## Layout

```
app/                      Compose UI, DI wiring
core/model/               project-owned models (pure Kotlin)
core/common/              dispatchers, logging, redaction (pure Kotlin)
core/testing/             shared test doubles
domain/                   pipeline contracts and coordinator (pure Kotlin)
data/settings/            DataStore-backed settings
data/translation/         ML Kit on-device translator, in-memory cache
platform/accessibility/   accessibility text acquisition
platform/overlay/         overlay rendering
docs/                     architecture, features, systems, decisions
```

The domain modules deliberately do not use the Android Gradle plugin, so platform types cannot
leak into the translation pipeline. See `CLAUDE.md` and `docs/architecture.md`.
