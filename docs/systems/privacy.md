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

## Review: captured images (V2)

Carried out when manga mode became usable, as this document asked for.

**Frames are never written anywhere.** There is no file, stream, `MediaStore`,
or `Bitmap.compress` call anywhere in the capture or accessibility modules —
checked, not assumed. A frame exists as one bitmap, is recycled at the end of
the scan that used it, and only the most recent one is held.

**Frames never leave the device.** Recognition and translation are both ML Kit
on-device models. No network permission is involved in either.

**Frames are never logged.** Diagnostics carry counts, sizes and element ids.

**Content rules apply unchanged.** `DefaultSensitiveContentPolicy` works on
`TextElement.text`, so masked fields and long digit runs are rejected on the OCR
path exactly as on the node path. `isProtected` is never set for OCR, and does
not need to be: Android draws a password field as bullets, so that is what a
screenshot contains, and the masked-text rule catches it.

### Two gaps the review found, both fixed

**Scope was not applied to manga mode at all.** The node path checks it, but
manga mode suspends the node path — so with manga mode on, walking into any app
meant reading the whole screen with no policy applied. A capture reads
everything on display, which makes scope matter *more* here than for nodes, not
less. The scan loop now checks the foreground package, and reading the launcher
went from happening to not: measured 0 recognitions on the home screen against 7
on the comic page.

**Per-app privacy exclusions could not match.** They key on
`SourceIdentity.packageName`, and OCR elements carried none, so a user who had
excluded their banking app would have been silently unprotected in manga mode.
Elements now carry the foreground package. Scope and privacy remain separate
lists, as they must — this fixed the plumbing, not the policy.

### Known, unfixed

ML Kit's native layer logs recognised text to logcat under the `native` tag,
outside our control. Local only, and unreadable by other apps since Android 4.1,
but "we do not log screen content" and "screen content is not logged" are
different claims and only the first is true.

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
