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

## LanguageResolver

Centralize:

- `FollowSystem` resolution
- `AutoDetect` representation
- locale normalization
- supported-language validation

Other modules should not reimplement locale policy.

## Cache Interaction

Cache identity must distinguish language parameters sufficiently to prevent incorrect cross-language reuse.
