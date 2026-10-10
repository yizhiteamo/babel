# System — Translation Cache

## Goal

Avoid repeated translation work for equivalent requests.

## Cache Identity

At minimum distinguish:

- normalized source text
- source language information when relevant
- target language
- provider/translation mode when outputs are not interchangeable

Never use only source text as the cache key.

## Persistence

In memory for everything (`InMemoryTranslationCache`, LRU of 500).

One exception, on disk (`DurableTranslationCache`): **a game's labels** —
names and interface words, text from `TextSourceType.GAME` without sentence
punctuation (`GameLayout.isSentence`). Without it a name changed its
translation after every restart (「レフ」 as 雷夫, then 列夫). At most 2000
entries, in `noBackupFilesDir/game-labels.json`, deleted by `clear()`.
Dialogue, and anything from the accessibility or manga paths, is never written.

## Invalidation

Use explicit schema/version changes when cache compatibility changes.
