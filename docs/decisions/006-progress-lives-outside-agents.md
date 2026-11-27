# ADR 006 — Progress Lives Outside CLAUDE.md

## Status
Accepted

## Decision
`CLAUDE.md` does not track active version or milestone progress.

Use:

- `docs/roadmap.md` for version-level status
- GitHub Issues for detailed current progress (amended by ADR 012; it used to be `docs/milestones/`)
- Git for implementation history

## Reason
This minimizes churn and keeps default agent context stable and small.
