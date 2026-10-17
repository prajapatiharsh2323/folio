# R3 — Annotations (mobile)

Session M-7 (Oct 17, 2026). Commit `7ffd693`. 93/93 tests green
(82 → 93: +5 AnnotationLocators, +6 QuoteCardContent)…

## What ships

1. **Persisted highlights render on reopen** — the headline result, with a
   finding that removed a whole planned subsystem:
   - **Readium 3.0.0 anchors decoration DOM ranges from `locator.text`
     (highlight/before/after) via Hypothesis' `TextQuoteAnchor`** — verified in
     the toolkit source `readium/navigator/src/main/assets/_scripts/src/utils.js`
     (`rangeFromLocator`). CFI fragments are only a CSS-id fallback. Decoration
     routing is keyed by `decoration.locator.href` (exact `Url` equality) in
     `DecorableNavigator.changesByHref`.
   - Therefore **no CFI parser is needed** to re-render persisted annotations
     (previous session's assumption in note 17 was wrong). A saved schema-1
     annotation is enough: parse `location` → spine index
     (`ProgressBridge.cfiSpineIndex` for `epubcfi(...)`; exact/leading-slash map
     for href fallbacks → `AnnotationLocators`), rebuild
     `Locator(href = readingOrder[idx].url(), text = quote/prefix/suffix)`, and
     the WebView decorates the matched text. "quote+context is authoritative"
     (the PWA contract) is literally what Readium anchors on — the two stacks
     anchor identically.
2. **Annotation context menu** — tap a highlight → bottom sheet: quote preview,
   4 PWA `HIGHLIGHT_COLORS` chips (`#FFE58A/#B7E4B0/#A9D1F5/#F5B7CC`), note
   editor (Save), delete, share. Edits bump `updatedAt` and persist via
   `LibraryManager.saveAnnotation` (upsert by id; newer-wins merge contract
   holds). `HighlightManager.onDecorationActivated` surfaces the event
   (`runOnUiThread`) with the live annotation by `event.decoration.id`.
3. **Quote-card share images** — pure `QuoteCardContent` (clamps quote ≤280 /
   note ≤160 chars at word boundaries with ellipsis, wraps, 6 tests) → Android
   painter: 1200×630 charcoal `#23242F` card, violet `#715CFC` brand edge +
   highlight-color band, serif bold quote, italic note, `title · author`
   attribution, "— Folio" wordmark. PNG → `cacheDir/quote-card/` → new
   `androidx.core.content.FileProvider` (authority `${applicationId}.fileprovider`,
   `res/xml/file_provider_paths.xml` scoped to `cache-path quote-card/`) →
   `ACTION_SEND image/png` + quote/note text.

## Files

- `core/annotation/AnnotationLocators.kt` (+5 tests) — primitive core, no Url.
- `core/annotation/QuoteCardContent.kt` (+6 tests) — pure layout math.
- `reader/HighlightManager.kt` — boot/update/remove + activation plumbing;
  now takes `Publication` (navigator's `publication` is protected).
- `reader/AnnotationMenu.kt`, `reader/QuoteCard.kt`, `ReaderActivity`
  (menu state + action handlers), `AndroidManifest.xml`, `file_provider_paths.xml`.

## Constraints respected

- schema-1 annotation shape untouched (no fixture churn needed).
- Readium pinned 3.0.0; pure-arg cores keep JVM tests free of Android/Readium
  runtime values (hot note 10).

## Open / next

- Device smoke still BLOCKED (no AVDs/system images) — persisted-highlight
  anchoring is verified at the source level, not on-device.
- Edge: exact-duplicate quotes in a resource anchor to the first occurrence
  (TextQuoteAnchor disambiguates with prefix/suffix; multi-occurrence drift
  possible) — acceptable for v1, PWA has the same behavior.
- R3 remainder: dictionary/translate (Hindi↔English) + per-chapter highlight
  list (library-screen work next). R2b comfort list still pending.
