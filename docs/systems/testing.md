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

The pages and images to test against — a reading page, a manga page, touch
targets — are in `docs/testing/`, with its README explaining what each one is
for and how to serve it. Use those rather than improvising a fixture: they were
built to exercise specific behaviour, and a fresh one usually misses it.

## Device-testing traps

Found the hard way, each one having cost a wrong diagnosis:

- **`uiautomator dump` disconnects the accessibility service while it runs**,
  which tears down the pipeline and resets manga mode. Read state from `logcat` and
  `screencap` instead — a UI dump taken to check a result is what destroys
  it. Measured again while smoke-testing V2: a dump taken to read the home
  screen reported 文字翻译 未运行 and 漫画模式 不可用 while both were in
  fact running, and the reading was believed long enough to start a hunt.
- **`:app:connectedDebugAndroidTest` uninstalls `com.babel`** when it finishes, taking the DataStore (including a configured API key) and `getExternalFilesDir` (including downloaded models) with it. Run the app's instrumentation by hand instead — `adb install -r` both APKs, then `adb shell am instrument -w com.babel.test/androidx.test.runner.AndroidJUnitRunner`. Library modules are safe: they uninstall only their own test package.
- **An ONNX session must be used under the same lock that owns its life.**
  Fetching it under a lock and then running inference outside one lets
  `release()` free it mid-call: that is a native `SIGSEGV`, not an
  exception — uncatchable, and it takes the process and the accessibility
  service with it. Found only on a real phone; the emulator needs a
  deliberately timed race to show it (`SessionReleaseRaceTest`).
- **Never turn the guest's network off to test offline behaviour.**
  `adb shell svc wifi disable` cuts the transport adb itself runs over: the
  device goes `offline` and nothing on the command line brings it back —
  `reconnect`, restarting the server and connecting to every port all fail,
  because the port is listening and the daemon inside cannot answer. The
  rescue is MuMu's own non-adb channel, `MuMuManager.exe sh -v 0 -c
  "svc wifi enable"` (under `MuMuPlayer/nx_main/`), then
  `adb connect 127.0.0.1:16384`. To test how the app behaves when no service
  is reachable, **point the endpoint at something unreachable**
  (`http://127.0.0.1:9/...`) instead: it produces the same `Network`
  failures and touches nothing.
- **Running the app's instrumentation leaves `accessibility_enabled` at 0.** An
  `am instrument` against `com.babel.test` runs in the app's own process and the
  service comes back unbound with the enabled-services list still naming it —
  the exact split state the capability check exists for. Rebind before judging
  any V1 result, or a working build reads as a dead one.
- **`adb install -r` unbinds the accessibility service.** Re-enabling it takes `am force-stop`, deleting `enabled_accessibility_services`, writing it again, and then **about fifteen seconds** before `dumpsys accessibility` reports it bound. Eight seconds reads as a failure to bind and invites a wrong diagnosis. On this phone that same window is when the grant gets taken away again — next bullet.
- **MIUI/HyperOS revokes the grant a few seconds after every enable, and it is
  not a Babel bug.** The vendor security centre rescans every app named in
  `enabled_accessibility_services` whenever the list changes, and drops the ones
  its antivirus dislikes — six to nine seconds after connect, every time. The
  discriminator is not the installer package but the scan verdict; the evidence
  and what can be done about it are in `docs/systems/capabilities.md`. When a
  device run shows no translations, grep for `try to remove: [com.babel]`
  before suspecting the pipeline.
- **Rebinding the service on an emulator takes a real change, and an unstopped
  package.** `am force-stop` puts the package in the stopped state, and the
  framework then refuses to launch its service at all
  (`ActivityManager: Unable to launch app com.babel … for service`); worse, a
  `settings put` of the **same value** notifies nobody, so the retry never
  happens. Both failures look identical from outside: `Bound services:{}` with
  `Binding services:{}` and `Crashed services:{}` also empty, which reads as a
  service that cannot start. The order that works is launch the app once, then
  write an empty list, then write the real one — bound within twelve seconds.

## Testing the pipeline

A collector of `renderUpdates` must run on `UnconfinedTestDispatcher`. A `StandardTestDispatcher` collector in `backgroundScope` is never resumed by `advanceUntilIdle`, and render assertions then pass against an empty renderer instead of failing.

### A scroll defect this has not been able to pin down

Seen once while smoke-testing V1 on the emulator and **not reproduced in three
replays of the same recipe**: after three rapid swipes, translations for list
items stayed on screen in a viewport that no longer contained the list, and the
body translations sat below their originals. It did not settle out — only a
reload cleared it. Recorded in `docs/milestones/v1.md` rather than acted on,
because changing rendering against a defect that appears one time in four is
guessing.

Two readings of it were wrong and are worth not repeating. It is not duplicate
rendering: the page carries both a heading and a list item reading "Letters from
the mainland", so two overlays with the same words are two real elements. And it
is not the coordinator dropping coordinates — on a matching key it assigns
`existing.element = element` and re-emits `show` with the new bounds.
