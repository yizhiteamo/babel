# System — Runtime State

## Goal

Represent translation runtime status explicitly rather than with a single boolean.

Conceptual state:

```text
Disabled
Starting
Running
Paused
Error
```

One coordinator should own authoritative runtime state.

UI observes state; platform services publish lifecycle/capability events into it.
