# System — Releases

When a build is published on GitHub, and what has to be true first. Set so
that fixes accumulate on `main` instead of each one becoming a release.

## When to release

Only for one of these:

1. **A milestone is complete** — its acceptance criteria in `docs/features/`
   are met. Raises the minor version: V3 complete is `0.3.0`.
2. **A defect that stops people using a published build**: a crash, lost
   settings or data, a privacy failure (text leaving the device when it should
   not), or a feature that does not work at all. Raises the patch version:
   `0.2.1`.

Everything else — translation quality, layout, speed, smaller bugs — waits
for the next of those. No more than one patch release a week; several
blocking fixes in a week ship together.

**Every release is confirmed with the user before anything is tagged,
pushed or uploaded**, whichever of the two reasons applies.

## Versions

`0.MINOR.PATCH` until the app is ready to be called 1.0. `versionCode` goes up
by one with every release, whatever the version name does. Releases stay
**pre-releases** until `docs/milestones/open-items.md` section C (wanted before
any release) is empty.

## What every release needs

- Built from a tag on the commit being released, in its own worktree, so
  unfinished work on `main` stays out (`v0.2.0` was built from before V3).
- Signed with the release key (`~/.gradle/gradle.properties`, never
  committed). Every release must use the same key, or an installed copy
  cannot be updated.
- One APK per CPU family plus `SHA256SUMS.txt`.
- **The release build tested on a phone**, not the debug build: shrinking has
  broken things the debug build never shows (ONNX Runtime's classes, renamed
  by R8, crashed manga mode on the first read of `0.2.0`). At least: text
  translation, manga mode on a real page, remote translation, the
  quick-settings tile, recovery after a force stop.
- Release notes in Chinese: what changed, which file to download, system
  requirements, known problems.
- Only the tag is pushed for the release itself; pushing `main` is a separate
  decision.
