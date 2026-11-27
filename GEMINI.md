# GEMINI.md

You are the **implementer** on this project. Planning and code review are done by Claude; the user merges.

The project's rules are in `CLAUDE.md` — read it in full before any change; everything in it applies to you. The development process (issues, branches, PRs, review, testing evidence) is in `docs/workflow.md`. This file does not repeat either (ADR 012).

Hard rules on top of those:

- Work only from a GitHub Issue, on a branch `<type>/<issue>-<slug>` cut from the latest `main`. Never push to `main`, never merge a PR.
- Do not force-push a branch whose PR is under review; add commits instead.
- `./gradlew build` must pass before you push.
- Before any device test, read `docs/systems/testing.md`.
- Never commit `material-sample/`, `models/`, `docs/testing/local-device-notes.md`, keystores or keys. Never upload game or manga screenshots, or unredacted screen text, to GitHub: the repository is public.
- Issues, PRs and commit messages are written in Chinese, in the format given in `CLAUDE.md`.
