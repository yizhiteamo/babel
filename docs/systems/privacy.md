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

## Visibility of screen reading

Screen reading should be visible to the user for as long as it lasts.

MediaProjection used to guarantee this: the platform forced a notification and a
status-bar recording indicator, and neither could be suppressed. Taking frames
through the accessibility service instead (ADR 009) removed the ceremony the
user disliked — and removed that guarantee with it.

What replaces it:

- Babel posts its own ongoing notification for the lifetime of manga mode. The
  platform no longer requires one; this document does.
- The accessibility service description states that manga mode takes pictures of
  the screen. It is shown at the moment the permission is granted, and it is now
  the only place the user is told.

The gap that remains, stated rather than glossed: if the user has denied
notification permission there is no indicator, and Babel cannot create one.

## What the logs contain

Babel's own diagnostics carry counts, sizes and element ids — never recognised
text. Measured across sessions: zero `Babel.*` lines containing Japanese.

ML Kit's **native** layer writes recognised text to logcat under the `native`
tag, which is outside Babel's control. Scope is limited — logcat is local and
since Android 4.1 an app cannot read another app's logs — but "we do not log
screen content" and "screen content is not logged" are different claims, and
only the first is true. A release build should be checked for the same
behaviour before shipping.
