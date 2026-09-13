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

`Paused` covers the **text acquisition path** only. Image translation is a
separate switch the user turns on deliberately, and a paused text path must not
swallow it — that combination was shipped once and reported as a bug: manga mode
recognised text and rendered none of it.

This narrows what the state acts on; it does not split the state in two. There
is still one coordinator and one authoritative state.

UI observes state; platform services publish lifecycle/capability events into it.
