# V3 — Immersive Game Translation

> **Draft for review (2026-10-07).** The decisions below were agreed in
> discussion; the numbers marked *estimate* are not measured yet, and the three
> spikes in `docs/milestones/v3.md` decide whether the design stands.

## Goal

Translate the text a game shows — dialogue, choices, pop-ups, menus — quickly
and accurately, drawn so that it looks like part of the game's own interface.
The measure of success is that the player's immersion is not broken: a
translation that would look worse than leaving the text alone is not drawn.

**General, not per game.** Nothing is tuned to one title. What decides what is
translated and how it is drawn are signals every game shares (what kind of
backdrop text sits on, whether a reading is stable, whether it is a number);
a per-game profile is an optional correction on top, never a requirement.

## Fast, accurate and good — at any pace of play

Story-heavy games, where the player taps rarely, are the main reason to
translate a game, but the design must not assume a slow player. A player who
taps through dialogue quickly, or moves through menus, must still get
translations fast, correctly read and well drawn — by whatever method that
takes. Concretely this rules out designs that only work when the screen sits
still for seconds: every stage is judged by how it behaves when the text changes
again before the last translation has landed (stale work is dropped, cached
interface text appears at once, nothing from an old line is drawn over a new
one).

## Decisions agreed before writing this

| Question | Decision |
|---|---|
| Lowest Android version | **14 (API 34).** Game mode needs a screenshot of the game's window alone (`takeScreenshotOfWindow`), so that once a translation covers the original, the next line is still visible to Babel. V1 stays at 8, manga mode at 11. |
| Where it runs | **Only in games.** Recognised by the system's game category, with a manual add for games that do not declare it. Never in other apps — the same concern as `open-items.md` #14, settled here for this mode. |
| Same language as the target | **Not translated.** Already the pipeline's behaviour (`Unchanged`); game mode additionally remembers a game's language once it is settled, so it stops asking. |
| Look | **Fit the game's UI.** Where the text sits on a plain or smoothly graded panel, the original is erased and the translation drawn in its place. Elsewhere it is not translated, or falls back to the existing box. |
| Seeing the original | **Long press shows the original**, release restores the translation. A short tap is handed straight on to the game, because tapping the dialogue box is how a player advances it. |
| Touches on a translation | **Babel's, as in manga mode.** Only the translation's own area takes touches; everywhere else goes to the game untouched. Passing a long press on to the game as well was considered and rejected: it would collide with seeing the original. A game whose long press means something (hold to skip) still gets it anywhere outside the translated text. |

## In Scope

- a **game mode** switch on the home screen, next to text translation and manga mode
- screenshots of the **game's window only**, not the whole display (API 34)
- **region change detection**: watching only the areas that hold text, and reading a region again only when it changed
- **text stability**: waiting until a typewriter-style line has finished appearing before reading it
- **horizontal OCR** on the on-device general engine; the manga recogniser is not used
- **what not to translate**, by general rules: numbers, levels, currency and times; text on complex artwork; readings that differ between frames (stylised lettering — measured in `open-items.md` #21); very short Latin labels
- **erase and redraw**: filling the original line from its own backdrop (row-wise interpolation from the pixels either side), then drawing the translation with the original's colour, alignment and line height, scaled to fit
- **long press for the original, short tap passed through** to the game (`dispatchGesture`)
- **caching** of interface text, which repeats constantly in a game
- **dialogue context** for the translator: the previous lines and the speaker, reusing manga mode's page context
- **cancellation** of work for a line that has already been replaced
- **throttling** when the screen is still, for heat and battery
- reuse of the common pipeline: `TextElement`, coordinator, cache, providers, overlay

## Optional (only if the required scope is met and there is time)

- a per-game profile: name and term list, and regions marked by hand as "translate only this" or "never this"
- showing a translation as the remote model streams it

## Out of Scope

- imitating the game's own font
- translating logos, title art and lettering drawn into the scenery
- game mode below Android 14
- games that block screenshots (`FLAG_SECURE`): the screenshot is black, so there is nothing to read; Babel says so instead of failing silently
- voice and audio
- a game-specific translation engine — V3 reuses the common one

## Acceptance Criteria

Measured on a real phone against `material-sample/game/` and a live session of
at least one game:

1. **Dialogue, choices and pop-ups** are translated; numbers, logos and title art are left alone.
2. **Latency:** from a line finishing on screen to its translation appearing, median within *2s with the remote chat provider* and *1s for interface text already cached* (*estimates to be confirmed by the spikes*).
3. **Fit:** on plain and graded panels the original is not visible under the translation, and the translation's colour and alignment follow the original's.
4. **Play is not interrupted:** a short tap on a translation advances the game as a tap on the game would; a long press shows the original.
5. **No feedback:** Babel never reads its own translation back as game text.
6. **Resource use:** with the screen still, capture slows down; a 30-minute session does not overheat the phone or run away with memory (the threshold to be set from the first measurements).
7. Nothing in the translation core is game-specific.
