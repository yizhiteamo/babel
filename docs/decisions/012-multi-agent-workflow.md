# ADR 012 — Multi-Agent Workflow Through Pull Requests

## Status
Accepted. Supersedes ADR 007.

## Decision
Work is split between three parties:

- **Gemini** (in Antigravity) implements, tests locally and on the device, and opens pull requests.
- **Claude** plans (turns milestone items into GitHub Issues) and reviews pull requests.
- **The user** relays between the two, makes product decisions, and merges.

Every change reaches `main` through a pull request that passed CI and was reviewed, and is squash-merged so that one Issue is one commit. `main` is branch-protected, administrators included. Releases still push only a tag (`docs/systems/release.md`).

**Work is tracked on GitHub, not in documents.** Tasks, progress, defects and decisions waiting on the user are Issues, grouped by Milestone; each active version has one pinned tracking Issue holding its acceptance status. The measurements behind a change go in its PR. `docs/milestones/` is retired: its files move to `docs/notes/` as unmaintained history, and its open-items index became Issues. This amends ADR 002 and ADR 006, which put detailed progress in `docs/milestones/`. Documents keep only what stays true: scope and acceptance criteria (`docs/features/`), how things work and why (`docs/systems/`, ADRs), and version-level status (`docs/roadmap.md`).

`CLAUDE.md` remains the single source of the project's rules. `GEMINI.md` and `AGENTS.md` are pointers to it plus a few implementer-only rules; they never copy its content. The process itself is in `docs/workflow.md`.

## Reason
One agent committing straight to `main` left no review step and no record of why a change was accepted. Separating the one who writes a change from the one who checks it catches what the writer cannot see, and the PR keeps the evidence next to the change.

The milestone files had become lab notebooks — `v2.md` alone ran to 3480 lines — mixing current truth with conclusions disproven further down, which every task then had to read. An Issue is closed when it is done, so what is open is exactly what is left.

ADR 007 deleted `AGENTS.md` because two full copies drifted. Pointer files keep that property: there is still one copy of every rule.

## Consequence
- All three parties share one GitHub account, and GitHub does not let an account approve its own pull request, so reviews are comments whose first line states the verdict. Branch protection requires CI, not an approval count.
- CI has no device and none of the local material (`material-sample/`), so device evidence must be written into the PR and checked in review.
- The repository is public: PRs carry no game or manga screenshots and no unredacted screen text.
