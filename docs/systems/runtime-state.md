# System — Runtime State

## Goal

Represent translation runtime status explicitly rather than with a single boolean.

Conceptual state:

```text
Disabled
Starting
Running
Paused
```

There is deliberately no `Error` here, and there used to be. It was never set,
and it was the wrong shape: a provider rejecting a key has not changed what the
coordinator is *doing* — it is still running, still tracking elements, still
rendering whatever the cache can answer. Folding that in breaks the controls
that read this, because `pause()` acts only while `Running` and the button is
offered only for `Running` or `Paused`. It would disappear at the moment
somebody is trying to fix their configuration.

Provider health is a second, orthogonal axis:
`TranslationCoordinator.providerFailure`. It carries the last failure that will
not fix itself, is latched only after several in a row, and is cleared by the
next success or by switching engine.

One coordinator should own authoritative runtime state.

`Paused` covers the **text acquisition path** only. Image translation is a
separate switch the user turns on deliberately, and a paused text path must not
swallow it — that combination was shipped once and reported as a bug: manga mode
recognised text and rendered none of it.

This narrows what the state acts on; it does not split the state in two. There
is still one coordinator and one authoritative state.

UI observes state; platform services publish lifecycle/capability events into it.
