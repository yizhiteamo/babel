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

Start simple. In-memory caching is acceptable initially if it satisfies current requirements.

## Invalidation

Use explicit schema/version changes when cache compatibility changes.
