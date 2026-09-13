# Test material

## What cannot be used as a test subject

Babel's own UI and the launcher are **out of scope** by default
(`docs/systems/scope.md`), so neither shows translations. Earlier end-to-end
checks used Babel's own settings screen; that no longer works and its absence of
overlays is correct behaviour, not a regression. Use the pages below with
chromium instead.

## `reading-sample.html`

A text-heavy English page used to verify acquisition and rendering against a
**third-party** app — Babel's own Compose UI is not sufficient evidence, because
its accessibility tree is shaped by code in this repository.

WebView is the important target: its tree is produced by Chromium, so node
granularity and container structure differ from native views. Both text-fitting
defects fixed in this area were found with this page and invisible on native UI.

The page deliberately mixes:

- multi-line paragraphs — the source bounds cover several lines, which is what
  broke type-size derivation
- headings of two sizes — different bounds heights
- a bulleted list — short, repeated-looking items
- the same sentence twice — checks that identity by occurrence index stays
  stable when text repeats

### Running it

```bash
adb push docs/testing/reading-sample.html /sdcard/Download/babel_test.html
adb shell am start -n com.android.chromium/com.google.android.apps.chrome.IntentDispatcher \
  -a android.intent.action.VIEW -d "file:///sdcard/Download/babel_test.html"
adb logcat -s Babel.AccessibilityService:* Babel.OverlayRenderer:* Babel.TranslationCoordinator:*
```

Expect roughly 7 text nodes for the first screen. A node count in the hundreds,
or a single node spanning the viewport, means container filtering in
`NodeTextExtractor` has regressed.


## `touch-link.html` / `touch-target.html`

A pair of pages for checking that the overlay does not swallow taps.

The link on the first page is deliberately wordy, so the translation covering it
is unmistakable in a screenshot. Tapping it should open the second page.

**Run the counter-test too.** A tap that succeeds proves nothing on its own — the
overlay might simply not have been over that point, which is how an earlier
attempt at this check produced a false pass. Temporarily drop
`FLAG_NOT_TOUCHABLE` from `OverlayWindow`, reinstall, re-enable the service
through the system UI, and tap the identical coordinate: it must *not* navigate.
Restore the flag afterwards.

```bash
adb push docs/testing/touch-link.html /sdcard/Download/touch-link.html
adb push docs/testing/touch-target.html /sdcard/Download/touch-target.html
adb shell am start -n com.android.chromium/com.google.android.apps.chrome.IntentDispatcher   -a android.intent.action.VIEW -d "file:///sdcard/Download/touch-link.html"
```

Confirm the link is covered before tapping — screenshot it, and read the link's
bounds from `uiautomator dump` to get the coordinate.


## `manga-source.html` / `manga-sample.png` / `manga-page.html` (V2)

Material for the manga OCR path.

`manga-source.html` is **not** the test material — it is the recipe. Render it in
chromium on device, screenshot it, and crop to content to produce
`manga-sample.png`. Going through a real screenshot means the sample carries
genuine glyph rasterisation and antialiasing rather than synthetic shapes, and
it avoids using copyrighted manga.

`manga-page.html` is what gets opened during a test run, and it contains nothing
but an `<img>`. **This is the important part**: if the sample were live HTML
text, the accessibility service would read it directly and the run would say
nothing about OCR. With only an image, the pixels are the only way in.

Check this before trusting a result, but check the right thing: the browser's
own tab title and address bar are text and *will* be picked up (2 nodes, as
measured). What must be absent is any Japanese — no node in the page content
area, nothing with kana or kanji. Zero total nodes is the wrong bar and will
look like a failure when nothing is wrong.

```bash
adb push docs/testing/manga-sample.png /sdcard/Download/manga-sample.png
adb push docs/testing/manga-page.html  /sdcard/Download/manga-page.html
adb shell am start -n com.android.chromium/com.google.android.apps.chrome.IntentDispatcher   -a android.intent.action.VIEW -d "file:///sdcard/Download/manga-page.html"
```

The OCR feasibility probe reads the PNG directly from
`data/translation/src/androidTest/assets/`:

```bash
./gradlew :data:translation:connectedDebugAndroidTest   -Pandroid.testInstrumentationRunnerArguments.class=com.babel.data.translation.mlkit.MlKitJapaneseOcrProbeTest
adb logcat -d | grep OCR_PROBE
```
