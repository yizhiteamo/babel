# System — Accessibility Acquisition

## Goal

Acquire text exposed by Android accessibility APIs and convert it into project-owned `TextElement` objects.

## Responsibilities

- manage service lifecycle
- observe relevant content changes
- identify supported visible text
- extract text and bounds
- exclude sensitive/irrelevant nodes
- normalize into `TextElement`
- deduplicate noisy events
- signal removed/stale elements

Do not call translation providers directly.

## Unsupported Content

If useful accessible text is unavailable, the active text-only phase should fail gracefully rather than silently invoking future visual-translation systems.

## Which path owns a screen

Both acquisition paths are driven by the one service, and only one of them may
translate a given screen: they feed the same coordinator, so running both would
translate the same text twice and stack two layers of overlays.

Manga mode being on means image translation is **allowed**, not that it takes
over. Ownership is decided per screen, by the rule this module already applies
to regions:

> Image translation exists to read what the text path **cannot**.

So the accessibility tree decides. Count the label-sized text nodes that fall
inside the app's content area:

- **none** — the page is a picture, and only a capture can read it. The image
  path owns the screen and the node path stands down.
- **some** — the node path can read this screen, so it does. The image path
  stands down and clears what it had drawn.

Both paths evaluate the rule themselves from live state rather than sharing a
flag, so there is nothing for the two coroutines to race over.

Measured in Chromium (`MIN_CONTENT_LABELS`): a comic page reports 0 labels
inside its content area, an article reports 8. A page part-way through loading
can briefly report 1–2, which is why the threshold sits in the gap rather than
at 1.

### Standing down means clearing

Whenever the image path stops owning a screen — the node path taking it back,
leaving scope, manga mode switched off, or **the user walking into a different
app** — its translations come off. That last case had no handling at all and was
reported from a device as bubbles left behind on whatever was opened next;
nothing else notices it, because the change detector compares pixels and one
white page after another does not clear its threshold.

Two races make this more than a flag:

- A scan that was already running when the screen changed publishes afterwards,
  which would undo the clear. `CaptureTextSource` counts clears and drops a scan
  whose screen went away while it was being read.
- The tree describes the new screen before the pixels show it. A frame is only
  recognised once it has stopped moving (`FrameChangeDetector.hasSettled`),
  otherwise the outgoing page's text is read onto the incoming one.

## What drives a scan

Both paths are event-driven, on the same `onAccessibilityEvent` signal, each
through its own conflated channel so that one falling behind cannot swallow the
other's wake-ups. Both debounce by `SCAN_DEBOUNCE_MS` before acting.

The image path also keeps a fixed timer, for apps that report no content changes
at all. It is a fallback, not the driver: measured on a device, driving the image
path from the timer alone cost 3.0s of a 5.9s wait, because the tree describes a
new page long before the timer next fires. The timer's interval is not lowered to
compensate — every tick takes a full screen capture, so a faster timer pays that
forever, while an event fires only when something moved.

Two drivers, one scanner: a scan holds a full-screen bitmap for seconds, so the
second caller skips rather than queueing. Whatever provoked it will still be on
screen when the running scan finishes or the next tick comes.
