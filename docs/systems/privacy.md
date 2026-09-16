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

## Text may leave the device, and only when asked

This used to say that nothing left the device and that the app held no network
permission. **The second half is no longer true.** Babel declares
`android.permission.INTERNET`, and it buys exactly one feature: translation
through a remote endpoint the user configures (ADR 010). On-device translation
was measured against a hosted model and reaches about half the quality at 579MB,
which made the ceiling worth offering a way past.

What holds:

- **It is off by default, and off is two things being false.** A route has to be
  selected *and* the service configured. Neither alone sends anything. What
  counts as configured depends on which service — an address and a model for a
  chat endpoint, a key for DeepL — but the shape of the gate does not change.
- **There is more than one possible destination, and the user names it.** The
  chat route goes wherever its address points, including a machine on the user's
  own network; DeepL goes to DeepL. Adding the second did not widen what leaves
  the device, only where it may go, and only when explicitly chosen (ADR 010).
- **Frames never travel, under any setting.** Only recognised text does.
- **The privacy policy runs first, unchanged.** `DefaultSensitiveContentPolicy`
  rejects protected and password-like text before any provider is called, and
  providers are reachable only through the translation layer — which is what
  makes that ordering a guarantee rather than a habit (ADR 005).
- **The interface says so where the switch is**, not in a submenu, and the
  warning is shown while the feature is on rather than standing permanently.
- **The credential cannot print itself.** `ApiKey.toString()` reveals nothing, so
  a settings object reaching a log does not take the key with it. It is stored in
  the app's private DataStore: readable by this app, not hardware-backed, and
  never sent anywhere but the service it belongs to.
- **Failures log a status, never a body.** A rejected request routinely quotes
  the request back, sometimes with the key in it.
- **Transport is https, except to the user's own machine.** The network security
  config permits cleartext to loopback only (`localhost`, `127.0.0.1`, `::1`,
  and the emulator's `10.0.2.2`); every other host is https or nothing. That
  exception exists so a locally hosted model is usable at all — it is the one
  configuration where the text never reaches the internet, and refusing it would
  have pushed those users to a hosted service instead (ADR 010).

## Review: captured images (V2)

Carried out when manga mode became usable, as this document asked for.

**Frames are never written anywhere.** There is no file, stream, `MediaStore`,
or `Bitmap.compress` call anywhere in the capture or accessibility modules —
checked, not assumed. A frame exists as one bitmap, is recycled at the end of
the scan that used it, and only the most recent one is held.

**Frames never leave the device.** Recognition is an on-device ML Kit model, and
a frame is recycled at the end of the scan that read it. This holds under every
setting, including the one below — what a remote translator receives is
recognised *text*, never a picture.

**Frames are never logged.** Diagnostics carry counts, sizes and element ids.

**Content rules apply unchanged.** `DefaultSensitiveContentPolicy` works on
`TextElement.text`, so masked fields and long digit runs are rejected on the OCR
path exactly as on the node path. `isProtected` is never set for OCR, and does
not need to be: Android draws a password field as bullets, so that is what a
screenshot contains, and the masked-text rule catches it.

### Two gaps the review found, both fixed

**Scope was not applied to manga mode at all.** The node path checks it, but
the image path takes over the screens that path cannot read — so with manga mode
on, walking into any app meant reading the whole screen with no policy applied. A capture reads
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
