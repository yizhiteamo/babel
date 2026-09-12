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
