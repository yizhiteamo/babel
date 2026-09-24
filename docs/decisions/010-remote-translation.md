# ADR 010 — Remote Translation, Off By Default

## Status
Accepted, amended twice — see the first bullet under Shape, where the
"one request shape covers everything" argument is narrowed by measurement, and
**Named services, as presets** below.

## Decision

Babel may translate through a remote, user-configured, OpenAI-compatible chat
endpoint. It is **off unless the user both selects it and configures it**, and
every other part of the app continues to work without a network.

## Reason

On-device translation was measured and it tops out well short of what users
expect. Three engines on the same thirteen hand-transcribed balloons
(`docs/milestones/v2.md`):

| | Quality | Size |
|---|---|---|
| ML Kit (on-device, shipped) | `先生も汗拭きシート使いますか?` → 你用汗水表吗？ | ~30MB per language |
| opus-mt int8 | → 老师也洗过手脚吗? | 146MB |
| opus-mt unquantised | → 老师也用擦汗纸吗? | 579MB |
| A hosted model | → 老师也用擦汗湿巾吗？ | — |

The best local option reaches roughly half the distance and asks 579MB for it,
and there is no intermediate size — quantising either half of the model is
already worse than quantising neither. Closing the rest of the gap is not
available from anything that runs on the phone.

So the choice is not "local or remote". It is "accept a visible quality ceiling"
or "let the user decide to spend their privacy on it". This makes that a
decision the user takes, knowingly, rather than one the app takes for them.

## What this costs, stated plainly

`docs/systems/privacy.md` could previously say that frames and text never leave
the device and that the app holds no network permission. **The second half is no
longer true**: `android.permission.INTERNET` is now declared. The first half
holds while the feature is off, which is its default state.

Frames still never leave under any setting. What can travel is recognised text,
after the privacy policy has already excluded what it excludes — providers are
reachable only through the translation layer, which is what makes that ordering
guaranteed rather than incidental (ADR 005).

## Shape

- **Two request shapes: an OpenAI-compatible chat endpoint, and DeepL.**
  *Amended.* This originally read "not a named service", on the grounds that one
  shape covers the hosted model this was asked for, the several providers that
  copy its API, and anything the user runs on their own machine — so naming a
  vendor would buy nothing and exclude all of that.

  The first half still holds and the chat route is unchanged. What the argument
  missed is that a comic balloon is *short*, and short input is where an
  instruction-following model stops following instructions. Measured on this
  project's own pages, a chat model answered `…あ` with `哈哈` and replied to
  `…あんまりわかんないケドッ` with 「我不会翻译日语，也不会参与翻译」
  (`docs/milestones/v2.md`). That same finding is what chose opus-mt over a local
  LLM, in its own words: a narrow translator "cannot refuse or ramble".

  DeepL is that reasoning reaching the network. It is not a vendor of the same
  shape — a translation API takes text and a target language, with no
  instruction to disobey — so it is a second implementation rather than a second
  endpoint to configure. It also asks the user for less: one key, no model name,
  no address, the host derived from the key's `:fx` suffix.

  What it gives up is context: a chat model can be told these are comic
  balloons, and this cannot. Which wins on a given page is a measurement, so
  both stay and the user picks. `RemoteTranslator` is the choice;
  `RoutingTranslator` stays a two-way question because that is the one the
  privacy gate asks.
- **Two independent switches, and the key is not one of them.** `provider`
  selects the route and `RemoteProviderSettings` says where to reach it. Both
  are required, so neither a stray selection nor a half-filled form can start
  sending. `isConfigured` asks only for an endpoint and a model: a hosted
  service answers 401 without a credential, which is visible and fixable, while
  a model on the user's own machine wants none at all. Requiring one made that
  second case impossible to save — the save button stayed disabled and this gate
  stayed shut — so the one configuration where the text never reaches the
  internet was also the only one the app refused to accept.
- **The key is a type, not a string.** `ApiKey.toString()` never reveals it, so a
  settings object that reaches a log cannot take the credential with it.
- **The remote route is not wrapped in `FragmentingTranslator`.** That wrapper
  splits a line at its ellipses and measurably helps ML Kit, which cannot use
  context; it measurably hurts a stronger model, which can. The wrapper belongs
  to the engine, not to the pipeline.
- **Cleartext is permitted to loopback and nowhere else.** "Anything the user
  runs on their own machine" speaks plain HTTP, and since API 28 the platform
  blocks that by default — so the offer above failed with a network error for
  exactly the users who wanted to keep their screen text off the internet. The
  network security config opens `localhost`, `127.0.0.1`, `::1` and the
  emulator's `10.0.2.2`; a hosted service stays https-only. A config matches
  hosts and not ranges, so a phone reaching a desktop at `192.168.x.x` still
  needs https or `adb reverse` — a limit of the mechanism, not a policy choice.

## Alternatives considered

- **Ship opus-mt at 579MB.** Rejected as the *only* answer, not on principle: it
  is half the improvement for a large download, and it does not reach what was
  asked for. It stays a candidate for the on-device route.
- **Bundle a key.** Impossible to do safely in a client application, and it would
  make the network the default rather than the choice.
- **A Babel-operated relay.** It would centralise everybody's screen text on
  infrastructure this project does not have, in exchange for convenience. Not
  worth it at any scale this is at.

## Named services, as presets

*Amendment.* "Not a named service" was about the **protocol**, and that still
holds: one OpenAI-compatible shape covers every hosted provider and anything the
user runs themselves. What it was read as, and became in the interface, is that
the user should type the address.

That does not survive contact with use. A service's own documentation gives a
base URL, and `https://api.deepseek.com` alone answers 404 — the path is the
part nobody publishes and everybody gets wrong. Setting this up for real took
being told the exact string.

So `ChatPreset` names a few services and fills in the whole address and a
starting model. The vendor names are a convenience, not a constraint:

- **`Custom` is always in the list**, and picking a preset still leaves both
  fields editable. Pointing Babel at something of your own is what the original
  rule was protecting, and it stays a first-class configuration rather than a
  fallback.
- **A local server is one of the presets**, not an afterthought — it is the only
  configuration where recognised text never reaches the internet, so it should
  be the easiest one to reach, not the one you have to know a URL for.
- **The model stays a text field.** Model names change faster than an app ships,
  and a picker that cannot name this month's model is worse than a box. The
  preset fills one in; the user changes it.
- The list is deliberately short. Every entry is an address that can be wrong,
  and a wrong preset is worse than none.

## Two services, two credentials

One `apiKey` served both routes, so configuring the second destroyed the first
one's credential: entering a DeepL key wiped the chat key, and switching back
sent the DeepL key to the chat endpoint, which answers 401. Two services cannot
be configured at once if they share one box.

`chatKey` and `deepLKey` are now separate, and `apiKey` reads whichever the
selected service uses. Settings written before the split hold one key, and it is
migrated to the service that was selected when it was written — never to the
other one, which is precisely the failure being fixed.

Only one service is in force at a time; `service` says which. Nothing falls back
from one to the other, and the home screen now says which is in effect, because
the only other way to find out was to read a translation and guess at its style.

## Checking the configuration

A wrong key was invisible twice over: nothing on save said so, and nothing at
use time did either — translations simply did not appear, which looks the same
as every other failure. `TranslationRuntimeState.Error` existed and was never
set.

`RemoteProbe` makes **one real request** with the draft settings and reports
what the service said. Real, because an address can parse and still 404, a key
can be well-formed and still be revoked, and a model name can be spelled
perfectly and not exist on that account — only the status separates those, which
is why `ProbeResult` is shaped like the statuses rather than like a boolean.

It uses the actual translator rather than a hand-written request: a second
implementation of the auth header, the payload and the path would be three
things that have each been wrong here at least once, and a copy that passes
while the real one fails is worse than no check.

Advisory, never a gate — somebody configuring this on a train should still be
able to save. Surfacing failures at *use* time is a separate piece of work and
is not done yet.
