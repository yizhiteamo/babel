# ADR 007 — Single Agent Instruction File

## Status
Accepted

## Decision
The project constitution and the agent operating guide are merged into `CLAUDE.md`. `AGENTS.md` is deleted.

References to `AGENTS.md` in ADR 002 and ADR 006 now point at `CLAUDE.md`. The constraints those ADRs express are unchanged.

## Reason
Two files had to be kept in sync while their content did not overlap: one held principles, the other held build commands and the module map. Merging leaves a single source of truth.

## Consequence
Other AI tools that read `AGENTS.md` by convention (Codex, Cursor) no longer pick up the project constraints automatically. If such a tool is adopted, point it at `CLAUDE.md`.
