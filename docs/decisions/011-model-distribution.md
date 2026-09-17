# ADR 011 — How the Models Reach a Device

## Status
Accepted

## Decision

The balloon detector **ships inside the package**. manga-ocr is **fetched on
request**. A device that has neither still translates, on the path V2 shipped
with.

## Reason

V2 measured its way to a working manga pipeline and then left the last question
open, in its own words:

> How the file reaches a user's device — bundled or fetched — is a separate
> question, and one worth answering after these numbers rather than before them.
> (`docs/milestones/v2.md`)

The numbers arrived. What they did not change is that **nobody could run any of
it**: the weights are read from `getExternalFilesDir(null)/models/`, a directory
only `adb push` fills. Every measurement in that milestone was taken on a device
somebody had pushed 128MB to by hand. A fresh install fell back to grouping
recognised lines by position and reading them with a general recogniser —
the path that produced `koisu` where the page said something else.

Remote translation did not rescue this. DeepL translates faithfully whatever it
is given, including nonsense: a good translator cannot repair bad recognition.

So the question is not whether to distribute the models but which, because they
are not the same size and do not buy the same thing.

| | Size | Balloon grouping | Recognition |
|---|---|---|---|
| Nothing | — | by position; sound effects and interface text included | wrong |
| **Detector only** | **11MB** | **correct, no interface noise** | wrong |
| Both | 128MB | correct | correct |

The detector is the cheap half. Against a per-ABI package of roughly seventy
megabytes it is about a fifth, and it asks the user for nothing. manga-ocr is
seven-eighths of the weight for the other half, and only a reader who actually
uses manga mode should pay it.

## What this costs, stated plainly

- **The package grows by 11MB.** Measured rather than assumed: the debug APK is
  201MB, but 174MB of that is native libraries for four ABIs, and a real release
  splits per ABI. Eleven megabytes against that is noticeable and cheap.
- **The repository grows by 11MB, permanently.** A binary in git history cannot
  be made smaller later without rewriting it. Accepted knowingly: the
  alternative is a build that cannot be reproduced from a clone.
- **Babel becomes a redistributor**, which obliges it to carry the licence and
  attribution. Running a pushed file never did. See Attribution below.
- **A downloaded model can fail** in ways a bundled one cannot — a truncated
  file, a spent disk, a connection that dies at 90%. That is why the download
  verifies before it installs.

## Shape

- **A pushed file still wins.** `OnnxBubbleDetector` prefers
  `getExternalFilesDir(null)/models/detector.onnx` and falls back to the asset,
  so a different export can be tried on a device without a rebuild. The
  measurement loop that produced this milestone keeps working.
- **The bundled model is read into memory, not copied to disk.** 11MB once at
  load, rather than a second copy of the same bytes living on the device.
- **`isAvailable` stays.** It is now false only when a load has failed rather
  than when a file is missing, but `DetectingPageReader`'s fallback is tested and
  documented, and reading an asset can still fail. It also asks again after a
  scan finds nothing, because availability is only fully known once a load has
  been attempted — without that, the first page after a failure renders nothing.
- **Zero balloons is still not a fallback.** A page with no dialogue genuinely
  has none, and falling back there would put the sound effects straight back.

## Attribution

`ogkalu/comic-text-and-bubble-detector` is Apache-2.0. That was checked when the
model was chosen, and a GPL-3.0 alternative several desktop tools use was
rejected on exactly this ground (`docs/milestones/v2.md`) — a decision taken
before it was needed, and needed now.

Redistribution triggers section 4 of that licence: the licence text and the
attribution travel with the package. They live in
`platform/capture/src/main/assets/licenses/`, beside the model they describe, and
the app shows them. Shipping them where no user can see them would satisfy the
letter and miss the point.

manga-ocr is fetched from its original host by the user's own request, so Babel
does not redistribute it. It is listed anyway.

## Alternatives considered

- **Bundle everything (128MB).** Doubles the package for a capability most
  installs never use, and puts an 87MB encoder in git history.
- **Download everything.** Saves 11MB and costs every user a download before
  manga mode does anything better than it does today. The detector alone is
  worth having with no user action at all.
- **Play Asset Delivery.** The right mechanism for exactly this, and premature:
  `applicationId` is still a placeholder and no release channel exists. Choosing
  a store-specific delivery mechanism before choosing a store is backwards.
