# ADR 008 — Manga Rendering Strategy

## Status
Accepted, with the opacity conclusion amended — see **Amendment: the ghost was
avoidable** at the end.

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

## Rejected: inpainting the erased area

Comparable desktop tools — [manga-image-translator](https://github.com/zyddnys/manga-image-translator)
is the reference point — erase the original with LaMa or Stable Diffusion
inpainting, and their showcase output has no trace of it. Adopting that was
considered and rejected, for a reason that is not obvious and is worth writing
out so it need not be re-derived.

**Inpainting cannot reduce the ghost.** Compositing is per pixel:

```
result = 0.8 × ours + 0.2 × what is on screen
```

Where an original stroke sits, the screen holds black. However perfectly the
layer beneath our text is reconstructed, that pixel comes out as:

```
0.8 × 255 (reconstructed white) + 0.2 × 0 (original stroke) = 204
```

Identical to filling the area with flat white. The ghost originates in the lower
layer; nothing done to the upper one removes it.

Inpainting would improve exactly one thing here: blending the overlay's edges
into a complex background — screentones, gradients, sound effects painted across
artwork. That benefit is real but secondary, and does not justify 30–200MB of
model, inference latency and heat, unless real material shows a high proportion
of non-flat bubble backgrounds. `docs/milestones/v2.md` records what to measure.

## Not a like-for-like comparison

The desktop tools operate under different constraints, so their output is not a
target we are failing to reach:

- They batch-process image files offline; we composite over a live screen. They
  are not subject to the opacity cap at all — they rewrite the pixels.
- They require the reader to export pages and run them through a pipeline first;
  we work inside whatever comic app the user already has.
- They can spend seconds per page on a GPU; we answer a page turn.

Their approach is better at erasure. Ours is better at being there when you turn
the page. Comparing only the first is how a sound trade-off gets mistaken for a
defect.

## Consequences

- A faint ghost of the original text remains. This is accepted, and its cause is
  a platform constraint rather than something to fix in rendering code.
- Background sampling becomes a requirement of the capture path, not an
  optimisation: without it this decision provides no benefit over V1.
- If the ghost proves unacceptable on real material, the decision to revisit is
  fullscreen takeover, with gesture forwarding priced in — not a rendering tweak.

## Amendment: the ghost was avoidable

This ADR said the original always shows through at 20%, and treated that as a
property of the platform. The arithmetic it gave was right — `0.8 × ours +
0.2 × theirs`, and no amount of work on the upper layer changes the lower one —
but the premise underneath it was not examined: that the overlay must be one
window covering the whole screen with touches passing through it.

`maximum_obscuring_opacity_for_touch` caps opacity for an untrusted overlay that
**obscures the screen while letting touches past**. It exists to stop a
screen-covering window nobody can touch from tricking the user. A window the
size of a speech bubble that *does* take touches is not that, and is not capped.
Measured: one window per translation, touchable, `dumpsys` reports `mAlpha=1.0`
where the full-screen window reported `0.8`. The original is genuinely covered.

The cost is a touch landing on a translation being consumed rather than passed
on. Verified on a scrolling page with six translations on screen: a swipe
between them still scrolls the page. Tapping a translation hides it, which is
both the way out of a swallowed tap and the natural way to see the original.

The earlier experiment that seemed to settle this removed `FLAG_NOT_TOUCHABLE`
from the *full-screen* window and left the screen unusable. That result was
real, and it answered a different question than the one it was taken to answer.

What still stands: inpainting is not worth its cost, because sampling a flat
colour is equivalent where the background is flat, and 25 of 26 real bubbles
measured flat enough.
