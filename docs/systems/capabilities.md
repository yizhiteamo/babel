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

Measured on a HyperOS device: the vendor security centre watches
`enabled_accessibility_services`, scans the APK of every service named there
whenever the list changes, and drops the ones its antivirus dislikes — six to
nine seconds after the user switches Babel on, every time, deterministically.
The process survives; only the binding goes. Nothing in the app prevents it
(`CLAUDE.md`, device-testing traps).

Two consequences for this layer:

- The accessibility check must read **both** `ACCESSIBILITY_ENABLED` and the
  enabled-services list. They come apart, and reading only the list once had the
  app reporting the service as granted while nothing was bound.
- `UNAVAILABLE` is the right report — the fixable state, which the user can act
  on, as against `UNSUPPORTED`, which no user action changes.

### The reading goes stale, and this is where it shows

`AndroidCapabilityChecker` refreshes on `ON_RESUME` and not otherwise, which is
a poor fit for a grant that disappears on a timer. The revocation lands in the
gap: the user enables the service in system settings, returns to Babel —
`ON_RESUME` fires and reads a grant that is still live, because the scan has not
finished — and seconds later the grant is gone while the screen still says
已开启 and 已就绪. Reproduced on device: twenty-five seconds after the list was
rewritten without Babel in it, the permission card was still green.

Polling on resume is wrong for any device, not just this one — the user can
switch the service off from the notification shade without Babel ever leaving
the foreground. The settings are observable; the check should observe them.
