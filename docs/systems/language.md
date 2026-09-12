# System — Language

## Goal

Represent language behavior without assuming a fixed source or target language.

## SourceLanguageMode

```text
AutoDetect
Manual(language)
```

Default: `AutoDetect`

## TargetLanguageMode

```text
FollowSystem
Manual(language)
```

Default: `FollowSystem`

## Separation

Keep separate:

1. system language
2. app UI language
3. source translation language
4. target translation language

## App UI language

The app's own interface ships in English (the default fallback) and Simplified
Chinese, through ordinary Android string resources. It follows the system
language; there is no in-app switcher, because Android 13+ already offers a
per-app language setting and `res/xml/locales_config.xml` declares which
languages Babel provides so that setting can find it.

**Changing the UI language must not change what text is translated into.**
`AndroidSystemLocaleProvider` therefore reads `LocaleManager.getSystemLocales()`
on Android 13+, falling back to the system configuration on older versions where
no per-app locale exists.

Neither `Locale.getDefault()` nor `Resources.getSystem().configuration` is
adequate: **both reflect the per-app locale on Android 13+**. This was measured,
not assumed — with the configuration read in place, setting a per-app locale of
English moved the translation target from `zh` to `en`, exactly the failure the
separation exists to prevent. The bug was invisible until the UI was localised,
because before that nobody had a reason to set a per-app locale.

To re-check after changes:

```bash
adb shell cmd locale set-app-locales com.babel --locales en
# UI turns English; "Translate into" must still read zh on a Chinese device
adb shell cmd locale set-app-locales com.babel --locales ""
```

Known trade-off: `values-zh` matches every Chinese region, so users in
traditional-script locales see Simplified Chinese. Splitting into
`zh-rCN` / `zh-rTW` is deferred until someone needs it — the wording genuinely
differs and would need a reviewer who reads traditional Chinese.

## LanguageResolver

Centralize:

- `FollowSystem` resolution
- `AutoDetect` representation
- locale normalization
- supported-language validation

Other modules should not reimplement locale policy.

## Cache Interaction

Cache identity must distinguish language parameters sufficiently to prevent incorrect cross-language reuse.
