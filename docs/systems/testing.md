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
- **`:app:connectedDebugAndroidTest` uninstalls `com.yizhiteamo.babel`** when it finishes, taking the DataStore (including a configured API key) and `getExternalFilesDir` (including downloaded models) with it. Run the app's instrumentation by hand instead — `adb install -r` both APKs, then `adb shell am instrument -w com.yizhiteamo.babel.test/androidx.test.runner.AndroidJUnitRunner`. Library modules are safe: they uninstall only their own test package.
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
  `am instrument` against `com.yizhiteamo.babel.test` runs in the app's own process and the
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
  device run shows no translations, grep for `try to remove: [com.yizhiteamo.babel]`
  before suspecting the pipeline.
- **Rebinding the service on an emulator takes a real change, and an unstopped
  package.** `am force-stop` puts the package in the stopped state, and the
  framework then refuses to launch its service at all
  (`ActivityManager: Unable to launch app com.babel … for service` — quoted
  from before the rename; the id is `com.yizhiteamo.babel` now); worse, a
  `settings put` of the **same value** notifies nobody, so the retry never
  happens. Both failures look identical from outside: `Bound services:{}` with
  `Binding services:{}` and `Crashed services:{}` also empty, which reads as a
  service that cannot start. The order that works is launch the app once, then
  write an empty list, then write the real one — bound within twelve seconds.

- **A library module's instrumentation wipes its own external files dir.**
  `:platform:capture:connectedAndroidTest` uninstalls the test package when it
  finishes, and that clears
  `/sdcard/Android/data/com.babel.platform.capture.test/files/` — models and
  pushed pages with it. So both have to be put back **immediately before every
  run**, not once:

  ```
  T=/sdcard/Android/data/com.babel.platform.capture.test/files
  adb shell "mkdir -p $T/models $T/comic-sample"
  adb shell "cp /sdcard/Android/data/com.yizhiteamo.babel/files/models/* $T/models/"
  adb push comic-sample/<page> $T/comic-sample/
  ```

  Forgetting the models gives `manga-ocr available: false` and a test that
  skips while reporting success, which reads as "nothing to see" rather than as
  "nothing ran".

- **Changing `applicationId` migrates nothing, and the models are the expensive
  part.** A new id is a new app to Android: private storage (the DataStore, so
  the configured API key), the external files dir (117MB of recogniser weights),
  the overlay permission and the accessibility grant all belong to the old id
  and stay with it. The weights need not be re-downloaded — they live on shared
  storage and `adb` can copy them straight across before the new package is
  installed:

  ```
  NEW=/sdcard/Android/data/com.yizhiteamo.babel/files/models
  adb shell "mkdir -p $NEW"
  adb shell "cp /sdcard/Android/data/com.babel/files/models/* $NEW/"
  ```

  **`chmod` after copying, or the app cannot read what it was given.** `cp` runs
  as `shell`, and on the phone the copied directory and files stay owned by
  `shell` with mode 660 while their parent belongs to the app — so the app sees
  nothing and offers to download 116MB again. `chmod 777` the directory and
  `chmod 666` the files and it reads them. **The emulator does not need this**
  (its FUSE layer maps ownership by path), which is how the step got missed the
  first time: the migration was rehearsed there and the recipe written up as if
  it were complete.

  The API key cannot come across — it is in app-private storage — so it has to be
  entered again. Uninstall the old package as well, or two accessibility
  services compete for the same screens.

- **The phone's log buffer rolls over faster than a test round takes.** Dumping
  with `adb logcat -d` after a 45-second round returned **zero** `Babel.*` lines
  while the app was working perfectly — other apps had pushed them out of the
  shared ring. It reads exactly like a dead pipeline, and cost a hunt: the
  service was bound, the permissions granted, the screen simply had no overlays
  because none had been asked for yet. Capture by process instead —
  `adb logcat -d --pid=$(adb shell pidof <applicationId>)` — and raise the ring
  with `adb logcat -G 32M`. The emulator is quiet enough that neither is needed,
  which is why the habit did not exist.
- **`dumpsys accessibility | grep -c 'label=Babel'` does not answer "is it
  bound".** The label appears in more than one block, so a service that is
  enabled-but-unbound — the split state the capability check exists for — counts
  as 1 and reads as healthy. Scope the grep to the block:

  ```
  adb shell dumpsys accessibility | sed -n '/Bound services/,/Enabled services/p'
  ```

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
