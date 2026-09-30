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

### 1. Stale overlays after a fast fling, seen once in 34 runs

`docs/milestones/v1.md` → *Open: stale overlays after rapid scrolling*

Seen once, then **33 attempts failed to reproduce it** across four gesture
shapes, two orientations and two timings.

**Next lead: a much longer page.** `docs/testing/reading-sample.html` is about
two screens, so every fling — even a single short one — ends pinned against the
end stop. The state never produced is a page *coasting to a halt in the
middle*, which is the only place a scan of a still-moving tree is possible.

### 7. Vertical punctuation is not rotated

`platform/overlay/.../VerticalTranslationView.kt` (class doc)

`ー`, `「`, `」` should turn 90° in real vertical setting. They do not, so they
look wrong on every Japanese page. Self-contained: the change is in the drawing.

### 13. First bubble after a fling takes ~3.6s on a phone

`docs/milestones/v2.md` → *真机出泡慢：三个假说全错，真因是后备定时器在抢锁*

The wasted work is fixed (doomed tick scans 39 → 0). The **user-visible number
barely moved** and the reason is structural — neither dominant term was touched:

- waiting for the fling to stop: **~1.43s** (measured, 1416–1543ms)
- capture → settle → detect → OCR → translate: **~1.7s**

Anything further means starting to read before the page fully stops, or making
that 1.7s shorter. Both are larger changes than the one already made.

### 14. Manga mode reads any app that does not expose its text

`docs/systems/privacy.md` → *Known, unfixed*

Two gates stand between a foreground app and a full-screen capture: a deny-list
of four packages, and "fewer than 3 label-sized nodes". A photo viewer, a
full-screen video, a game, a map, an ID photo all pass both. The mode also
restores itself after a process death.

**Recorded rather than fixed by an explicit decision.** The options and their
costs are listed with it; the cheapest is binding the mode to the first app
entered after it is switched on.

### 18. The detector calls drawn faces text

`docs/milestones/v2.md` → *气泡漏读：量完之后，两条已记录的结论都被推翻*

On `kr-mag-01`, two of the three regions that read as nothing are **drawn facial
expressions**, not lettering. The recogniser declining them is correct; the
detector offering them is not. Each costs one OCR call.

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

### 15. `ChatPreset.label` is hardcoded English

`domain/.../settings/ChatPreset.kt`

`Custom` and `Ollama (local)` show in English inside a Chinese UI. `ChatPreset`
lives in the pure-Kotlin `:domain`, which cannot reach Android resources, so
this needs the label to move or the id to be mapped in `:app`.

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
