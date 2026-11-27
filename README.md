# Babel

An Android immersive translation app. It translates text shown by *other* apps and renders the
translation in place, so reading, comics, and games stay readable rather than becoming a
copy-paste chore.

## Status

V1 (text translation) and V2 (manga translation) are complete; `0.2.0` is published as a
pre-release under GitHub Releases. V3 (game translation) is being built.

See `docs/roadmap.md` for version-level state, and the repository's Issues and Milestones for
work in progress. How changes are made is in `docs/workflow.md`.

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
./gradlew build      # lint and all unit tests, as CI runs it
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
domain/                   pipeline contracts, coordinator, manga and game rules (pure Kotlin)
data/settings/            DataStore-backed settings
data/translation/         on-device and remote translators, caches
platform/screen/          screen-frame contracts
platform/accessibility/   accessibility text acquisition, screenshots
platform/capture/         manga OCR and balloon detection
platform/game/            game text from the game's window
platform/overlay/         overlay rendering
tools/                    soak test and model evaluation scripts
docs/                     architecture, features, systems, decisions, workflow; notes (history)
```

The domain modules deliberately do not use the Android Gradle plugin, so platform types cannot
leak into the translation pipeline. See `CLAUDE.md` and `docs/architecture.md`.
