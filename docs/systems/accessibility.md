# System — Accessibility Acquisition

## Goal

Acquire text exposed by Android accessibility APIs and convert it into project-owned `TextElement` objects.

## Responsibilities

- manage service lifecycle
- observe relevant content changes
- identify supported visible text
- extract text and bounds
- exclude sensitive/irrelevant nodes
- normalize into `TextElement`
- deduplicate noisy events
- signal removed/stale elements

Do not call translation providers directly.

## Unsupported Content

If useful accessible text is unavailable, the active text-only phase should fail gracefully rather than silently invoking future visual-translation systems.
