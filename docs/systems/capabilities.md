# System — Capabilities

## Goal

Represent whether required platform capabilities are available and usable.

Capability answers: "Can the system perform this operation?"
Runtime state answers: "What is the translator currently doing?"

Keep them separate.

Capability checks should be centralized rather than duplicated across UI, services, and repositories.

## A granted capability can be taken back

A grant the user gave can be withdrawn underneath the app while it runs, so a
capability reading has a shelf life.

### Measured on a HyperOS device

`com.miui.securitycenter.remote` watches `enabled_accessibility_services`. Every
change to the list hands each named app's APK to `com.miui.guardprovider` to be
scanned, and an app that fails is dropped from the list:

```
VirusScanJobService: onChanged: [«another-a11y-app»/ItsService, com.babel/…BabelAccessibilityService]
Babel.AccessibilityService: accessibility service connected
AvlEngine: getVirusInfo packageName : com.babel virusLevel : 3
CacheInterceptor: Cache hit:«another-a11y-app» / Cache missed:com.babel
VirusScanJobService: try to remove: [com.babel]
Babel.AccessibilityService: accessibility service torn down
```

Everything quoted in this section — this excerpt and the `pm set-installer`
experiment below — predates the rename and so says `com.babel`; the app's id is
`com.yizhiteamo.babel` now. Device logs are kept as they were printed, and the
commands are left as they were actually run: rewriting evidence to match later
code is how a record stops being one.

### One observation after the rename, which is not yet a finding

The same build under `com.yizhiteamo.babel` was scanned on the same phone and
**kept**: the cloud cache missed it exactly as before
(`CacheInterceptor: Cache missed:com.yizhiteamo.babel`), but the local engines
returned a clean verdict and the line came back `try to remove: []`. The service
stayed bound.

That is **one run**, and the honest reading is that it narrows nothing on its
own: the analysis above already says the cache miss is necessary and not
sufficient, and that the local verdict decides. Whether the bare two-segment name
`com.babel` was itself part of what the engine disliked is unproven and would
take deliberate repetition to establish — the point of writing it down is that
the next person sees a data point rather than repeats the hunt.

The other app is redacted; what the excerpt has to show is only that a second
accessibility service on the same phone went through the same scan and stayed in
the list.

Six to nine seconds from connect to teardown, every time. The process survives;
only the binding goes. Babel does not switch itself off — there is no
`disableSelf`, `setServiceInfo(null)` or `stopSelf` in the tree — and there is
no crash, no ANR and no kill.

The discriminator is **not** the installer package. `pm set-installer com.babel
com.miui.packageinstaller` makes that field identical to the accessibility app
that survives the same scan, and Babel is still dropped. It is the verdict: the
surviving app is a cloud cache *hit*, known to the vendor's database, while a
self-signed local build is a *miss* and is judged by the local engines. Nothing
on the app side reaches this. Marking Babel trusted in 安全中心's antivirus
does, but that whitelist lives in `com.miui.guardprovider`'s private data and is
not reachable over adb, so it takes taps on the phone.

### Two consequences for this layer

- The accessibility check must read **both** `ACCESSIBILITY_ENABLED` and the
  enabled-services list. They come apart, and reading only the list once had the
  app reporting the service as granted while nothing was bound.
- `UNAVAILABLE` is the right report — the fixable state, which the user can act
  on, as against `UNSUPPORTED`, which no user action changes.

### Why the check observes rather than polls

`AndroidCapabilityChecker` used to refresh on `ON_RESUME` and not otherwise,
which is a poor fit for a grant that disappears on a timer. The revocation
landed in the gap: the user enables the service in system settings, returns to
Babel — `ON_RESUME` fires and reads a grant that is still live, because the scan
has not finished — and seconds later the grant is gone while the screen still
says 已开启 and 已就绪. Measured: twenty-five seconds after the list was
rewritten without Babel in it, the permission card was still green.

It now registers a `ContentObserver` on both keys instead. This was never a
vendor-specific problem — on any device the user can switch the service off from
the notification shade without Babel leaving the foreground. The overlay
permission is an app-op with no settings key to watch, so it still rides on
`ON_RESUME`.

Device-testing traps around all of this are in `docs/systems/testing.md`.

## A force stop takes the service out of the list

Reported by the user: every time the process was killed, accessibility had to
be switched on again in system settings. `dumpsys activity exit-info` on the
HyperOS test phone says what actually killed it (2026-10-04 to 10-07, the
builds' own reinstalls and the tests below left out):

| Reason in `exit-info` | Times | Importance |
|---|---|---|
| `USER REQUESTED / FORCE STOP`, `due to SwipeUpClean` — clearing Babel from recents | 5 | 125 |
| `OTHER KILLS BY SYSTEM`, `camera boost` (765MB PSS at the time) | 1 | 125 |
| `LOW_MEMORY`, already a cached process | 2 | 400 |

A force stop is what removes it: AOSP's package monitor drops a force-stopped
package's service from `enabled_accessibility_services`. Reproduced with the
recents screen's clear-all button, which logs as `OneKeyClean`: the list lost
Babel in the same second as the exit. A process merely killed keeps its place in the list.

### What does and does not keep it out of the cleaner's way

Measured with the clear-all button, since injected swipes on the recents
cards are ignored:

- **Not being in recents does not help.** With `excludeFromRecents` on the
  activity, the card was gone and `OneKeyClean` force-stopped the package
  anyway (`ProcessSceneCleaner: OneKeyClean: force-stop pkgName=…`) — it cleans
  background packages, not only tasks.
- **Locking the card in recents does.** Locked, clear-all ran and Babel stayed,
  process and service both. The lock lives in the vendor's `locked_apps`
  setting, per package — the old id `com.babel` was still locked there, and the
  renamed app was not until it was locked again.
- **A foreground service would not.** A bound accessibility service already
  runs at importance 125, the same as a foreground service, and the kills above
  were at 125. #11 in `docs/milestones/open-items.md` is closed on this.

### What Babel does about it

- **A switch that is not the service.** Clearing the app was also how the user
  turned translation off, so the quick-settings tile now does that instead
  (`MasterSwitch`, `BabelTileService`), and the service can stay on.
- **Putting it back after a force stop**, when the user has run
  `adb shell pm grant com.yizhiteamo.babel android.permission.WRITE_SECURE_SETTINGS`
  (`AccessibilityServiceRestorer`). Opening Babel then re-enables it; measured,
  connected within five seconds of opening the app, and the vendor's rescan
  returned `try to remove: []`. A service switched off by the user stays off:
  that unbinds the service and the restorer records it, while a force stop
  kills the process before anything can run — measured both ways.
- **The tile cannot do the putting back.** Clicked after a force stop, SystemUI
  logged the click and nothing in Babel ran: a stopped package's tile service
  is not started. Opening the app is the one way back in.
- **Babel's own switch page is not reachable.**
  `android.settings.ACCESSIBILITY_DETAILS_SETTINGS` is hidden API and ended in
  `Permission Denial` on this phone, so the button opens the accessibility list
  with Babel's entry passed as the highlight; HyperOS's list does not highlight
  it, and the user taps through 已下载的应用.

