# Open Items — V1 / V2

What V1 and V2 still owe, in one page.

Both milestones are `Complete` in `docs/roadmap.md`. Nothing here contradicts
that: the acceptance criteria are met, and the one criterion with an exception
carries it in writing (`docs/features/v1-text-translation.md`, scrolling).

**This file is an index, not a record.** Every item points at where the
evidence lives; nothing is restated here, because two copies drift and the
stale one is always the one that gets read. Close an item by deleting its entry
and saying so where its evidence lives.

---

## A. Defects a reader would notice

### 1. Stale overlays after a fast fling, seen once in 114 runs

`docs/milestones/v1.md` → *Open: stale overlays after rapid scrolling*

Seen once on the emulator, then **113 attempts failed to reproduce it** —
including the lead this entry used to name: 80 runs on a 30-screen page on a
real phone, flings coasting to a halt mid-page, checked against the page's real
element positions, manga mode on and off. None stale.

**No lead left.** Kept open only because it was seen; do not change rendering
for it. If it is seen again, capture overlay geometry against DevTools element
positions at that moment (`v1.md` describes the probe).

### 13. First bubble after a fling takes ~3.6s on a phone

`docs/milestones/v2.md` → *真机出泡慢：三个假说全错，真因是后备定时器在抢锁*

The wasted work is fixed (doomed tick scans 39 → 0). The **user-visible number
barely moved** and the reason is structural — neither dominant term was touched:

- waiting for the fling to stop: **~1.43s** (measured, 1416–1543ms)
- capture → settle → detect → OCR → translate: **~1.7s**

Anything further means starting to read before the page fully stops, or making
that 1.7s shorter. Both are larger changes than the one already made.

**The 1.7s is now broken down** (same section, *复测*): settling always takes a
second look (~590ms), detection ~360ms, and whether `ScrolledBalloons` reuses
the page decides ~840ms on its own. Three causes are fixed — the crop jumping
between images, an abandoned scan emptying the memory, and an 8px tolerance
tighter than the phone's detector jitter — and reuse now places **16 of 16**
flings on a real chapter (`v2.md` → *容差改为 16px*).

The scan read part-way and thrown away on 5–8 of 16 flings is fixed: Edge
reports a fling's end ~290ms after its last moving event, just past the quiet
window, and that scroll invalidated a scan whose frame already showed the
stopped page. First bubble median 2.1–2.2s → 1.9s (`v2.md` → *扫描读到一半被放弃*),
then ~1.75s once the balloons are detected while waiting for the second look
(`v2.md` → *等第二眼时预先检测*).

### 18. The detector calls drawn faces text

`docs/milestones/v2.md` → *气泡漏读：量完之后，两条已记录的结论都被推翻*

On `kr-mag-01`, two of the three regions that read as nothing are **drawn facial
expressions**, not lettering. The recogniser declining them is correct; the
detector offering them is not. Each costs one OCR call.

The opposite also happens: **unboxed text is missed on a tall frame.** A
1440×3200 screen squashed to 640×640 scores it 0.1–0.3 lower than two square
tiles do, which puts it on the 0.5 floor, so it comes and goes between frames.
Now read a second time in two halves once the page is on screen, which found
19 such pieces on two audits and nothing that was not lettering, without
moving the first balloon (`v2.md` → *实现 D 并真机验收*). Lettering that scores
near the floor in both can still come and go.

### 21. The same balloon reads differently from different scroll positions

`docs/milestones/v2.md` → *同一气泡每次读得不一样*

Why one title was seen translated two ways. Not the translation layer: the
cache is keyed on the text, so the text itself differed. Read through 82 fresh
windows, a stylised title gave **24 readings in 24**, a Latin logo 9 in 12, and
an ordinary balloon was one character wrong in 20 of 49 — the detector's box
height wanders by up to 80px, and the crop with it. Clean lettering reads the
same every time.

A reader sees it only when the page memory is dropped and a balloon is read
again. Re-edging the crop to the ink was measured and **rejected**: it steadied
the stylised titles (319 → 333 of 401 right) and unsettled clean dialogue,
which the current crop reads 97.5% the same (→ 93.2%, with new wrong
characters). What is left — trusting lettering on the art less, or keeping a
re-read balloon's old translation — waits for a reader to ask.

---

## B. Measured, deliberately not acted on

### 10. Peak memory 735MB PSS

`docs/milestones/v2.md` → the 真机实测 table (PSS 735MB / Native 487MB / RSS 812MB)

Measured at 1440x3200, proportional to pixel count. The test phone has 11.4GB
and was never killed. A smaller device is the open question.

### 12. Whole-page context costs ~8200 characters a page

`docs/milestones/v2.md` → *延迟代价：真机 A/B，四轮*

Per-request time rose **93ms (p=0.015)**, and first-bubble time did not move,
because OCR produces a balloon only every ~250ms and the four-way concurrency
never saturates. **Conditional, not current**: if OCR gets faster — a decoder
with a key/value cache — the provider becomes the pacing item and this surfaces.
The fix then is compressing the context, not removing it.

### 6. Name consistency does not engage on short pages

`docs/milestones/v2.md` → *人名一致那一半：短页上不触发*

`PageContext.agreed` is filled from `onTranslated`, and translations run
concurrently: a short page submits every balloon before the first answer
returns. Long pages, rate-limited by `maxConcurrentTranslations`, do engage.

---

## C. Wanted before any release

### 8. No app icon

`CLAUDE.md` → Conventions

`app/src/main/res/` holds no mipmap or drawable and the manifest declares no
`android:icon`, so the launcher shows the platform placeholder. (Deferred by
the user, not forgotten.)

### 11. No foreground service

`docs/systems/privacy.md` → *Visibility of screen reading*

Nothing keeps the process alive; the home screen explains the power settings
instead. That hands a platform problem to the user.

### 14. Manga mode reads any app that does not expose its text

`docs/systems/privacy.md` → *Known, unfixed*

Two gates stand between a foreground app and a full-screen capture: a deny-list
of four packages, and "fewer than 3 label-sized nodes". A photo viewer, a
full-screen video, a game, a map, an ID photo all pass both. The mode also
restores itself after a process death.

**Not to be fixed before it is used for real, by an explicit decision.** Two
real scenarios want opposite things: an app that is all comics wants the mode
on for the whole app (an allow-list, or binding to the app), while a comic
turning up inside another app — a chat, a feed — defeats any per-app gate and
wants a "translate this screen once" gesture instead. Which one dominates is a
usage question with no data yet, so the choice waits for it. Every option
starts with the same gate after the scope check in `considerImageScan`, so
waiting costs no code. The options and their costs are in `privacy.md`.

---

## Not items

- **The service looks enabled but unbound after `adb install -r`.** Operational,
  not a defect; the recipe is in `docs/systems/testing.md`.
- **Whether the translator still transliterates `선생님 줄게`.** `LineJoin` was
  the cause and is fixed, but nobody has read that page since with remote
  translation on. One glance settles it.

---

## Conclusions that were disproven

Kept because the *way* they were wrong is worth remembering; the text where they
live has been corrected in place.

| Claim | What it actually was |
|---|---|
| OCR misread `선생님` | It read `선생님 \| 줄게` exactly. `LineJoin` glued the lines into an unspaced run — the corruption was **between** OCR and the translator |
| Small balloons are a recall problem | Two of three are drawn faces, one is an ellipsis. Declining them is right |
| `SoundEffect.isDrawnNoise` is not wired in | It is, in `PageReader.assemble`, under `bubble.onArt` |
| `PageTextDumpTest` failed on a wiped directory | It could not compile; `./gradlew build` does not build `androidTest` |
| Manga mode's latency is a threshold being too small | Three thresholds checked, all adequate; a fallback timer was taking the lock |

The shape they share: **an inference written down as a measurement.** Sizes were
read as content, an output was read backwards to its input, a failure was
attributed to the last thing that had gone wrong before. When an entry here says
"measured", it should link to the numbers.
