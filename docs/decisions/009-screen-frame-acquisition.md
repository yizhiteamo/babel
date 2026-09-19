# ADR 009 — Where V2's Frames Come From

## Status
Accepted. Supersedes the MediaProjection decision embedded in ADR 008's
implementation notes; ADR 008's rendering strategy is unaffected.

## Context

V2 read the screen through MediaProjection. It worked, and the cost was paid
entirely by the user:

- A system consent dialog **every time** manga mode starts. From Android 15 the
  token cannot be reused, so this is not a first-run cost that goes away.
- A recording indicator in the status bar for the whole session.
- A `mediaProjection` foreground service, which the platform requires.

For a tool whose purpose is to disappear into someone's reading, the ceremony
around starting it is most of what the user experiences.

`AccessibilityService.takeScreenshot()` (API 30) asks for none of it. The user
has already granted the accessibility permission for V1, and the screenshot
capability hangs off that same grant — declared by `android:canTakeScreenshot`
in the service config, so it is disclosed where the permission is granted rather
than re-asked every session.

## Decision

Take frames with `AccessibilityService.takeScreenshot()`. Delete MediaProjection.

Manga mode requires Android 11 and reports itself **unsupported** below it. V1's
text translation still works down to minSdk 26; only manga mode is gated.

That state is distinct from **unavailable**, which means the accessibility
service is not running. Both block manga mode and they were once the same
answer, which turned out to be a bad one: the version requirement is permanent
and the service is one visit to system settings away, and a message naming both
led with the version on every device that already satisfied it. `isSupported`
asks about the device, `isAvailable` asks about the device *and* the connection.

No fallback to MediaProjection on Android 8–10. Two acquisition backends would
have to be maintained forever, and the one being kept for old devices is
precisely the experience this decision exists to remove.

`takeScreenshotOfWindow()` (API 34) would capture only the target app's window,
excluding the status bar and our own overlays. Not adopted now: it would add a
third branch, and frame-change detection already handles the overlay feedback it
would prevent.

## What this costs

**Screen reading became invisible.** MediaProjection forced a notification, and
`docs/systems/privacy.md` treated that as a feature rather than a nuisance — it
was what made screen reading visible for as long as it lasted. Nothing forces
one now.

So Babel posts its own ongoing notification for the lifetime of manga mode. The
platform stopped requiring visibility; the project has not. Where the user has
denied notifications there is no indicator at all, and the code says so out loud
rather than failing quietly.

The accessibility service description was also rewritten to state that manga
mode takes pictures of the screen. It is the copy shown at the moment the
permission is granted, and it is now the only place the user is told.

## Module consequence

Only a live `AccessibilityService` can take a screenshot, and that service is in
`:platform:accessibility`; the OCR pipeline is in `:platform:capture`. Platform
modules must not depend on each other, and `Bitmap` cannot enter the pure-Kotlin
`:domain`.

Both sides therefore depend on contracts rather than on each other:

- `:platform:screen` — a module holding one interface, `ScreenFrameSource`. It
  exists solely because the contract must mention `Bitmap`.
- `ImageTextScanner` in `:domain` — free of Android types, so the accessibility
  service can drive scanning without seeing the OCR module.

This keeps ML Kit out of the V1 path, which is the concrete reason the split is
worth its ceremony rather than merging the two modules.

## Consequences

- Manga mode is a toggle. No dialog, no recording indicator, no foreground
  service, and nothing to re-authorise.
- Android 8–10 lose manga mode entirely.
- Screenshots are throttled to roughly one per 333ms; a refused screenshot means
  "no frame this time", never a failed session.
- Frames still include our own overlays, exactly as MediaProjection's did, so
  frame-change detection remains necessary.
- `FLAG_SECURE` on our overlay is still not an option: measured on device, it
  blanks the entire mirror and takes the user's own screenshots with it.
