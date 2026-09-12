# Test material

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
