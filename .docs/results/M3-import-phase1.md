# M3 phase 1 — Import Pipeline Core (Result Summary)

**Session:** M-4 · **Date:** Oct 17, 2026 · **Commit:** fb6be02 (pushed) · **Tests: 43/43 green** (19 new)

## What was built

```
mobile/app/src/main/kotlin/dev/folio/mobile/core/
  importer/FileDetector.kt    content-based format detection (spec §6)
  importer/EpubImporter.kt    EPUB unarchive + DRM refusal + OPF/spine parse → ImportResult
  importer/TxtImporter.kt     TXT → paragraphs → clean HTML + EPUB 3 (Hindi detection)
  clip/EpubBuilder.kt         clean/index.html template + EPUB 3 builder (processClip parity)
mobile/app/src/test/kotlin/dev/folio/mobile/core/importer/
  EpubFixtures.kt             synthetic in-test EPUB builder (novel ×2 chapters, DRM variants,
                              plain zip, article epub) — no copyrighted fixtures committed
  FileDetectorTest.kt         6 · EpubImporterTest.kt 8 · TxtImporterTest.kt 5
```

## Key behaviors verified

- **Detection**: PDF magic; zip+`mimetype` = EPUB; strict-UTF-8 fatal decode → plain
  zips are `unknown` (TextDecoder parity, test-locked); HTML marker set incl.
  tag-regex; BOM-tolerant; empty → unknown.
- **DRM guardrail**: `rights.xml` → refuse; `encryption.xml` with non-font Algorithm
  → refuse; font-obfuscation algos (`idpf/2008/embedding`, `adobe pdf/enc#RC`)
  allowed; `CipherData` without algo → refuse. Legal rule: refuse, never circumvent.
- **EPUB parse**: prefix-tolerant `<dc:title|creator|language>` regexes (handles
  default xmlns and prefixed dicts, multiline); manifest id→href map attribute-order
  independent; spine reading order; href fallback chain exact→decoded→case-insensitive;
  per-chapter search text; word count; `reading/book.epub` = original bytes passthrough.
- **Plain-text extraction parity**: tags → single space (words never concatenate);
  common named entities + decimal + hex numeric decode; `&mdash;-class` entities left
  as literal text exactly like TS (test-locked).
- **EPUB 3 builder**: `mimetype` first + STORED (test asserts method==STORED and
  entry order); deterministic template → two identical TXT imports produce identical
  `clean/index.html` bytes and identical contentHash (cross-platform dedupe-safe).
- **TXT**: BOM strip, blank-line split, HTML escaping, `hi` when >30% of letters are
  Devanagari, chapter `ch-1/chapter_1.xhtml` href parity with TS.

## TS-behavior parity notes (locked by tests)

- `extractPlainText` leaves `&mdash;` etc. undecoded — same as TS (only a fixed
  entity set is decoded).
- Chapter plain text includes `<title>` + `<h1>` content before body — same as TS.
- Plain zip (non-EPUB) → `Unknown` — same as TS fatal decode.

## Next session

1. Read `.docs/MOBILE-TRACKING.md` first.
2. M3 phase 2: HTML importer — jsoup dependency + sanitizer Safelist port of the
   DOMPurify allow-list (spec §6; forbid script/style/iframe/object/form/svg/math +
   all `on*` handlers; `javascript:` URIs blocked; links get rel=noopener), image
   extraction cap 40, SingleFile data-URL images → files.
3. Also PDF importer — `com.tom-roush:pdfbox-android`, text extraction, ≥50 chars/
   page → `searchable: false` heuristic, locked-PDF error message.
