# ADR 008 — Manga Rendering Strategy

## Status
Accepted

## Context

V1 established that a translation overlay cannot be fully opaque: Android caps a
touch-passthrough overlay at `maximum_obscuring_opacity_for_touch` (0.8), so
roughly a fifth of the original always shows through. Removing
`FLAG_NOT_TOUCHABLE` restores full opacity but makes the screen unusable — both
configurations were measured on device.

V2 differs in a way that changes what is achievable. Its source is a captured
bitmap rather than another app's views, so the original text **can** actually be
erased, and the real background colour **can** be sampled — neither is possible
in V1.

## Decision

Render per bubble, over the original, with the background colour sampled from
the capture. Do not take over the whole screen.

The original text inside a bubble is masked out and the translation laid into
the cleared area, which is what `docs/features/v2-manga-translation.md` already
calls "original-text masking/removal".

## Reason

Sampling the background is what makes this good enough. On a white bubble:

| Region | Composite at 0.8 | Result |
|---|---|---|
| Bubble whitespace | `0.8×255 + 0.2×255` | 255 — the overlay's edges are invisible |
| Translated strokes | `0.8×0 + 0.2×255` | 51 — clearly legible |
| Original strokes left showing | `0.8×255 + 0.2×0` | 204 — a faint grey ghost on white |

V1 could not do this: with no MediaProjection it had to guess a background, the
guess rarely matched, and the whole overlay block stayed visible. Matching the
real background hides the overlay itself and leaves only a faint ghost of the
original strokes.

The alternative — a fullscreen, opaque, touchable overlay showing a fully
repainted page — would remove that ghost entirely. It was rejected because every
gesture would then have to be captured and forwarded to the app underneath.
Paging and pinch-zoom are the whole interaction surface of a comic reader, and
forwarding them through `dispatchGesture` costs latency and cannot reproduce
zoom faithfully. Trading working interaction for a faint ghost is a bad deal.

## Consequences

- A faint ghost of the original text remains. This is accepted, and its cause is
  a platform constraint rather than something to fix in rendering code.
- Background sampling becomes a requirement of the capture path, not an
  optimisation: without it this decision provides no benefit over V1.
- If the ghost proves unacceptable on real material, the decision to revisit is
  fullscreen takeover, with gesture forwarding priced in — not a rendering tweak.
