# System — Translation Scope

## Goal

Decide which apps are worth translating at all.

## Not the same as privacy

Two different questions, deliberately kept apart:

- **Scope** answers "is translating this app useful?"
- **Privacy** (`docs/systems/privacy.md`) answers "may this text leave the screen?"

They have different reasons and different defaults. A launcher is excluded
because translating it is useless, not because its contents are secret; a
password field is excluded even in an app that is fully in scope. Neither
substitutes for the other, so they are separate policies.

## Why a default exclusion list is needed

`docs/features/v1-text-translation.md` targets *text-heavy applications*.
Nothing about accessibility acquisition restricts it to those — it reports
whatever is on screen. Without a scope policy the product translates surfaces
where translation is actively harmful.

## Default exclusions

- **Launcher** — icon labels are proper nouns. A translated app name is not more
  readable, and users locate apps by name, so translating it makes them harder
  to find.
- **Input method** — key caps and the keyboard's own settings and emoji panels
  are controls, not reading material, and the keyboard changes constantly.
  Note that an ordinary keyboard popup does not reach this rule: the active
  window stays the host app, so the app keeps being translated (verified). The
  exclusion matters when the IME itself owns the focused window.
- **Babel itself** — otherwise the app's own settings screen is covered by its
  own output.
- **System UI** — the status bar, notifications and volume controls are
  transient overlays of their own; covering them is disruptive.

Everything except the system UI package is resolved at runtime rather than
hardcoded, so the policy follows whichever launcher and keyboard the user
actually has.

## The image path leans on this list far harder than the node path

Both paths ask [isInScope], and the answer means something different to each.

The node path reads **named nodes**: text an app deliberately exposed to
accessibility. The image path reads **the whole display** as pixels. So the same
deny-list that merely avoids wasted work on one path is, on the other, the only
thing standing between manga mode and a screenshot of whatever is in front —
which is why the scan loop checks it separately rather than trusting the node
path to have done it (`docs/systems/privacy.md` records the review that found it
missing).

The second check on that path, `imagePathOwnsScreen`, is easy to mistake for a
safety rule and is not one. It asks **"can the text path read this screen?"** —
a layout question, answered by counting label-sized nodes inside the content
area. It does not ask whether the screen *should* be read. A screen with no
exposed text passes it, whatever that screen contains.

Between a deny-list of four packages and a layout test, an app that draws its
own text is in scope by default. That is a deliberate consequence of the current
policy rather than an oversight, and what it means in practice — including
device evidence and the options for narrowing it — is recorded under
**Known, unfixed** in `docs/systems/privacy.md`, not repeated here.

## Placement

The decision runs at the acquisition entry point, before the node tree is
walked: an out-of-scope app should cost nothing, not merely be filtered out
later.

Leaving an app's scope must also clear what is already rendered, or overlays
from the previous app linger over the new one.

## Not in this phase

Per-app user configuration. The default list covers the surfaces where
translation is clearly wrong; letting users curate their own list needs settings
UI and persistence, and `docs/systems/settings.md` defers per-app settings until
a phase requires them.
