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
