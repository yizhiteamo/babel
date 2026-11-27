# ADR 012 — Multi-Agent Workflow Through Pull Requests

## Status
Accepted. Supersedes ADR 007.

## Decision
Work is split between three parties:

- **Gemini** (in Antigravity) implements, tests locally and on the device, and opens pull requests.
- **Claude** plans (turns milestone items into GitHub Issues) and reviews pull requests.
- **The user** relays between the two, makes product decisions, and merges.

Every change reaches `main` through a pull request that passed CI and was reviewed, and is squash-merged so that one Issue is one commit. `main` is branch-protected, administrators included. Releases still push only a tag (`docs/systems/release.md`).

`CLAUDE.md` remains the single source of the project's rules. `GEMINI.md` and `AGENTS.md` are pointers to it plus a few implementer-only rules; they never copy its content. The process itself is in `docs/workflow.md`.

## Reason
One agent committing straight to `main` left no review step and no record of why a change was accepted. Separating the one who writes a change from the one who checks it catches what the writer cannot see, and the PR keeps the evidence next to the change.

ADR 007 deleted `AGENTS.md` because two full copies drifted. Pointer files keep that property: there is still one copy of every rule.

## Consequence
- All three parties share one GitHub account, and GitHub does not let an account approve its own pull request, so reviews are comments whose first line states the verdict. Branch protection requires CI, not an approval count.
- CI has no device and none of the local material (`material-sample/`), so device evidence must be written into the PR and checked in review.
- The repository is public: PRs carry no game or manga screenshots and no unredacted screen text.
