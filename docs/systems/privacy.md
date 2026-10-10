# System — Privacy and Data Handling

## Goal

Minimize unnecessary handling and persistence of user-visible screen content.

## Principles

- process only content required for translation
- exclude password-like/protected input
- avoid persisting raw screen text by default — the one exception is a game's
  short labels (names, buttons) and their translations, kept on the device so a
  name stays the same name (`docs/systems/cache.md`); never dialogue, never
  another app's text, never in a backup
- avoid full source text in normal logs
- route provider access through the translation layer
- make local vs remote processing boundaries explicit

## Text may leave the device, and only when asked

This used to say that nothing left the device and that the app held no network
permission. **The second half is no longer true.** Babel declares
`android.permission.INTERNET`, and it buys **two** features, neither of which
happens unless the user asks:

1. **Translation through a remote endpoint the user configures** (ADR 010).
   On-device translation was measured against a hosted model and reaches about
   half the quality at 579MB, which made the ceiling worth offering a way past.
2. **Fetching the comic recogniser's weights** (ADR 011), on a button press.
   That request carries no screen content — it asks a model host for a file by
   name, and what comes back is checked against a pinned hash before it is used.

A second permission came with the download: `ACCESS_NETWORK_STATE`, declared in
`:platform:capture` beside the code that needs it. It answers one question —
whether this connection is charged by the megabyte — so the app can say so
before offering to spend 117MB. It reads no traffic and identifies no network.

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
`SourceIdentity.packageName`, and OCR elements carried none, so an excluded app
would have been silently unprotected in manga mode. Elements now carry the
foreground package.

**This fixed the plumbing and nothing else, which is worth stating plainly
because the earlier wording did not.** `DefaultSensitiveContentPolicy` is
constructed at `app/.../di/PipelineModule.kt` with **no arguments**, so
`excludedPackages` is the empty set, and no screen anywhere writes to it. Every
sentence about "an app the user excluded" therefore describes a path **no user
can reach today**. Scope and privacy remain separate lists, as they must — but
one of them currently has no contents and no way to acquire any.

### Known, unfixed

ML Kit's native layer logs recognised text to logcat under the `native` tag,
outside our control. Local only, and unreadable by other apps since Android 4.1,
but "we do not log screen content" and "screen content is not logged" are
different claims and only the first is true.

#### Manga mode reads any app that does not expose its text

Asked by the user as a question — *will this fire in other apps?* — and the
answer is yes, by default, and it is worth being exact about when.

With manga mode on, two checks stand between a foreground app and a full-screen
capture (`BabelAccessibilityService.considerImageScan`):

1. **Scope**, which is a **deny**-list: the launcher, the keyboard, Babel and
   SystemUI. Everything else is in scope (`docs/systems/scope.md`).
2. **`imagePathOwnsScreen`** — fewer than `MIN_CONTENT_LABELS` (3) label-sized
   nodes inside the content area (`ContentArea`).

So the condition is "not one of those four, and the accessibility tree barely
exposes any text". A photo viewer showing one image, a full-screen video, a
game, a map, anything drawn with Canvas/Unity/WebGL, a scan or an ID photo — all
of them satisfy it. What then happens is the whole pipeline: **screenshot →
on-device OCR → and, if remote translation is configured and on, the recognised
text goes to the endpoint the user named.** Frames still never travel; text does.

Three things make this larger than it first looks:

- **The mode is persisted and restores itself** when the service reconnects
  (above). Left on and forgotten, it comes back after the process is killed.
- **Nothing narrows it per app.** The exclusion list is empty and unreachable,
  as the correction above now says.
- **Pausing is not a defence.** Pause stops the node path only — measured, with
  translation showing 已暂停, a comic page still made 21 provider calls. The
  interface says as much ("漫画模式是独立开关"); it is repeated here because it
  is easy to assume otherwise.

**The balloon detector is not a content gate, and this is measured rather than
argued.** From a device log taken on an English article page:

```
20:44:27.973 DetectingPageReader  detector found 2 bubbles
20:44:28.088 MangaOcr             balloon: encode 80ms, decode 23ms, 6 steps
20:44:28.091 CaptureTextSource    read 1 regions ... abandoned part way
20:44:29.605 AccessibilityService image scan skipped: the node path owns this screen
```

Two "balloons" found in prose, one of them recognised. Note the order as well:
**the layout check is evaluated once, when a scan starts.** A screen that only
becomes readable while the scan runs has already been captured and recognised by
the time the node path claims it. And a chat bubble is shaped exactly like a
speech balloon, so "it only reads comics" is not a claim this detector supports.

##### The ways out, and what each costs

Recorded so the next attempt does not re-derive them:

- **Bind the mode to one app** — the first app entered after it is switched on
  owns it; every other app is skipped, and the home screen names the owner.
  No new settings screen, small change, and it grows naturally into the next
  option. Costs: switching readers means switching the mode off and on.
- **A user allow-list** — strongest and supports several readers at once. Costs
  an app-picker and persistence, which `docs/systems/settings.md` defers.
- **Ask on entering a new app** — clearest consent, but it interrupts reading,
  and Babel posts no notifications to ask through.
- **A content gate** — attractive and, as measured above, **not reliable**;
  worse, it only fires after the screenshot and the OCR, which is the step that
  matters most here.
- **Translate this screen once** — a gesture (a tile, a button) that reads the
  current screen a single time. The only option that serves a comic turning up
  inside another app, where every per-app gate fails; and every screen read is
  one the user asked for.

The options serve two different situations — an app that is all comics, and
a comic appearing inside some other app — and a real product probably wants one
of each. Untouched for now by the user's decision, until there is use to say
which matters more: the risk is recorded, the code is not changed, and it must
be settled before any release (`docs/milestones/open-items.md` #14).

## Visibility of screen reading

MediaProjection used to make screen reading visible whether anyone wanted it or
not: a forced notification and a status-bar recording indicator. Taking frames
through the accessibility service (ADR 009) removed both, and Babel then posted
a notification of its own for as long as manga mode was on.

**That notification is gone, by the user's decision (2026-10-07).** It had in
fact never appeared — Babel never asked for the notification permission Android
13 requires — and when that was found, the user chose immersion over an
ongoing notification, and declined an opt-in switch as one control too many.

What shows that the screen is being read now, and nothing else does:

- **At the moment of consent:** the accessibility service description says
  manga mode takes pictures of the screen; it is the text shown when the
  permission is granted.
- **On the home screen:** the manga mode row reads 正在读取屏幕 while it does.
- **In quick settings:** the Babel tile is lit while either path is on.

The cost, stated rather than glossed: while reading another app, with Babel not
open and the control centre not pulled down, nothing on screen says the screen
is being read. The translations drawn over the comic are the only sign — which
is the point of the mode, and also its only indicator.

### Manga mode comes back by itself, and why that is not a new decision

The mode is persisted and restored when the accessibility service reconnects
(`MangaModeController`). So after a phone's power management kills the process,
Babel can begin reading the screen again without the user touching anything —
which deserves to be written down rather than discovered.

Why it is nonetheless the right behaviour:

- The process did not end because the user ended it. Restoring is putting back
  the state they left, not deciding for them.
- Only their own actions are ever written. A mode taken down because the
  service went away writes nothing, so a capability failure can never be
  replayed later as consent.
- Nothing is restored without the accessibility service, which is a permission
  they granted explicitly and can revoke in system settings.
- A restored mode shows exactly as a hand-started one does: the home screen
  row and the tile follow `CaptureState`, not how the mode came on.

What Babel deliberately does **not** do is keep itself alive to make this rarer:
there is no foreground service (ADR 009 removed the one MediaProjection
required). It would buy nothing anyway — a bound accessibility service already
runs at a foreground service's importance, and what kills Babel is the user's
own cleaner force-stopping it (`docs/systems/capabilities.md`). The home screen
explains how to lock it in recents instead, and the user decides.

### The service is put back after a force stop, and why that is not a new decision either

A force stop also takes the accessibility service out of the enabled list,
which until now meant a trip through system settings after every clean. With
`WRITE_SECURE_SETTINGS` granted, opening Babel writes it back
(`AccessibilityServiceRestorer`). That is the strongest thing Babel does on its
own, so its limits are the point:

- **The user grants it, on purpose, from a computer.** No dialog in any app can
  give this permission; only `adb shell pm grant` can, and the home screen shows
  the command and what it does rather than asking for it.
- **Only a service the user had on, and only one nobody switched off.** Switching
  it off in system settings unbinds it, and so does the vendor's security
  centre revoking it; the service records both and the restorer leaves them
  alone. Only a force stop — which kills the process before anything runs —
  leaves the record saying the user wanted it.
- **Only its own entry.** Every other service in the list is kept as written.
- **Only when the user opens Babel.** A force-stopped app runs nothing until it
  is launched, so there is no background path that turns screen reading back
  on behind the user's back.

### Turning translation off is no longer turning accessibility off

The quick-settings tile pauses text translation and stops manga mode through
the same calls as the home screen's buttons (`MasterSwitch`), and turning it on
brings back exactly what it took down. Manga mode never comes on through it
unless it was on when the tile went off: it takes pictures of the screen.

## What the logs contain

Babel's own diagnostics carry counts, sizes and element ids — never recognised
text. Measured across sessions: zero `Babel.*` lines containing Japanese.

ML Kit's **native** layer writes recognised text to logcat under the `native`
tag, which is outside Babel's control. Scope is limited — logcat is local and
since Android 4.1 an app cannot read another app's logs — but "we do not log
screen content" and "screen content is not logged" are different claims, and
only the first is true. A release build should be checked for the same
behaviour before shipping.
