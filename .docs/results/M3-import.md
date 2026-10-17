# M3 — Import Pipeline COMPLETE (Result Summary)

**Session:** M-4 · **Date:** Oct 17, 2026 · **Commits:** fb6be02 (phase 1) → 9c1ec65 (HTML) → d5ca39d (PDF) → 98a263a (cleanup), all pushed
**Tests: 64/64 green** (`testDebugUnitTest assemblyDebug` both green)

## Delivered surface

```
core/importer/FileDetector.kt    content detection (spec §6): magic bytes, mimetype,
                                 HTML markers, strict-UTF-8 fatal decode
core/importer/EpubImporter.kt    EPUB: DRM refusal (rights.xml / encryption.xml), OPF
                                 parse (manifest+spine), href resolution, plain text
core/importer/TxtImporter.kt     TXT: BOM, blank-line split, Devanagari>30% → "hi"
core/importer/HtmlImporter.kt    HTML/SingleFile: jsoup sanitizer, article extraction,
                                 data-URI image extraction (cap 40 + dedupe)
core/importer/PdfImporter.kt     PDF (PDFBox-Android): pages as chapters, searchable
                                 heuristic, locked-PDF error
core/clip/EpubBuilder.kt         clean/index.html template + EPUB 3 builder
                                 (mimetype STORED first; deterministic → stable hash)
```

Tests: `FileDetectorTest` 6, `EpubImporterTest` 8, `TxtImporterTest` 5,
`HtmlImporterTest` 8, `HtmlSanitizerTest` 7, `PdfImporterTest` 6 + shared
in-test fixture builders (`EpubFixtures`, `PdfFixtures` — zero copyrighted files).

## Security-relevant decisions

- **jsoup protocol allow-lists are mandatory**: `Safelist.addAttributes("img","src")`
  alone permits ANY scheme (file:, javascript:). Locked with explicit
  `addProtocols(a[href] http/https/mailto; img[src] http/https/data)`.
  Test: `img src limited to safe protocols`.
- Hostile-page test asserts forbidden strings absent from BOTH clean/index.html AND
  the produced EPUB bytes (builder path inherits sanitized input only).
- DRM: refuse (never circumvent) — rights.xml, unknown EncryptionMethod algorithms,
  CipherData without algorithm; font-embedding obfuscation algos allowed (TS parity).

## TS-parity notes (test-locked)

- `extractPlainText`: tags → one space each; fixed entity set only (named beyond
  that, e.g. `&mdash;`, stays literal — same as TS).
- Chapter search text includes `<title>` + `<h1>` text before body (same as TS).
- Non-EPUB zip → `Unknown` (TS TextDecoder fatal behavior).
- PDF: no reading/clean editions; chapters `page-N` href `#page=N`; language "en".
- HTML/EPUB/TXT: original bytes preserved; reading edition for TXT/HTML is a
  built EPUB 3 (builder) while EPUB reading = original passthrough.

## PDFBox-on-JVM test infrastructure (reusable recipe)

tom-roush PDFBox-Android loads resources from Android assets; on the JVM it falls
back to `Class.getResourceAsStream`. Fixed by checking the AAR and copying into
`mobile/app/src/test/resources/com/tom_roush/`:

- `pdfbox/resources/glyphlist/` (glyphlist.txt, additional.txt, zapfdingbats.txt)
- `pdfbox/resources/afm/` (standard-14 metrics)
- `pdfbox/resources/ttf/` (LiberationSans-Regular.ttf — needed by `PDType1Font` clint)
- `pdfbox/resources/text/`, `fontbox/resources/unicode/`
- CJK cmaps (~3.2 MB) omitted intentionally; add only if CJK PDF fixtures are needed.
- `testOptions { unitTests.isReturnDefaultValues = true }` in `build.gradle.kts`
  absorbs direct android.util.Log calls.
- `PDType1Font` static init needs resources above → fixtures hand-roll PDF bytes
  (`PdfFixtures.buildPdf`) instead of using the PDFBox writer in tests.

## Wired dependencies (gradle/libs.versions.toml)

- `jsoup 1.18.1` (implementation)
- `pdfbox-android 2.0.27.0` (implementation) — namespace is **com.tom_roush.pdfbox**

## Deliberate deviations from PWA (logged, revisit-able)

1. HTML article extraction = structural heuristic, NOT Readability scoring.
   Adequate for saved/SingleFile pages; full scoring deferred to the clipper phase.
2. Identical images (same bytes) dedupe to one stored EPUB media item (zip cannot
   have duplicate entries; TS object-key model silently overwrites).

## Next steps (R1 — Readium EPUB reader)

1. Read `.docs/MOBILE-TRACKING.md` first.
2. Add Readium Kotlin Toolkit deps (readium-lcp? NO LCP/DRM — just
   `org.readium.kotlin-toolkit:readium-shared` + `readium-streamer` or the
   `readium` full toolkit, latest version compatible with Kotlin 2.0.x/AGP 8.7.x;
   check https://readium.org/readium-kotlin-toolkit/).
3. Targets for R1 core:
   - `ReaderActivity`: load EPUB from FileStore path via `EpubPublication.get`
     (publication is decrypted-free; DRM files already refused at import).
   - Paginated + scrolled, 5 themes from token palette, per-book prefs
     (fontSize/family/lineHeight/margin parity with PWA defaults 18px/1.6/20).
   - Locator↔progress mapping fed into schema-1 Progress model (byte-compat).
4. Add `PDFBoxResourceLoader.init(this)` to FolioApplication before any PDF import
   on device (first wiring when import UI lands in M4/UI phase).
5. Commit + push after every change; keep this file + tracking updated.
