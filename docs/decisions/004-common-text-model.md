# ADR 004 — Common Project-Owned Text Model

## Status
Accepted

## Decision
All acquisition methods convert output into a project-owned `TextElement` model before entering the translation pipeline.

## Reason
This prevents later phases from forcing downstream translation systems to depend on source-specific platform objects.
