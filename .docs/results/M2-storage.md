# M2 — Storage Layer (Result Summary)

**Session:** M-3 · **Date:** Oct 17, 2026 · **Commit:** b5d2a4a (pushed) · **Tests: 24/24 green**

## What was built

```
mobile/app/src/main/kotlin/dev/folio/mobile/core/
  store/FileStore.kt         interface (port of file-store.ts; FileStat, suspend fns)
  store/AppFileStore.kt      app-private impl: atomic writes, traversal guards, injectable root
  util/Hashing.kt            sha256Hex + createDocSlug (exact PWA-behavior port)
  importer/ImportResult.kt   ImportResult/ImportClean/ImportOriginal/ImportReading/SearchChapterText
  library/LibraryManager.kt  manifest commit-point manager (full port)
mobile/app/src/test/kotlin/dev/folio/mobile/core/
  store/AppFileStoreTest.kt      5 tests
  util/HashingTest.kt            5 tests (known sha256 vector, slug format/cap/Hindi fallback/diacritics)
  library/LibraryManagerTest.kt  9 tests
  model/TypesContractTest.kt     5 tests (from session M-2)
```

## Robustness wins over the PWA equivalent (OpfsStore)

1. **fsync before atomic rename** — a process kill / power loss can never leave a
   torn file at the target path (OPFS write/rename has no fsync primitive).
2. **Hard path-traversal guards** — `..` segments, absolute paths, drive letters all
   throw `SecurityException` at resolve time (spec §10 zip-safety rules now apply to
   the store layer too).
3. **Blocked non-recursive dir removal** — no silent empty-dir semantics drift.
4. **`damagedDocuments()` API** — explicit damage surfacing (spec: never silently dropped).

## LibraryManager behaviors (spec §4 rules, all tested)

- Write order: original → clean(+images) → reading → meta.json → **manifest LAST**.
- Manifest = commit point; corrupted manifest → clean reinit.
- Duplicate detection by contentHash (caller prompts skip/replace/keep-both).
- deleteDocument cascade: folder → search text → progress → annotations →
  collections membership → manifest rewrite (idempotent).
- recoverInterruptedImports(): uncommitted `docs/` folders surfaced for
  finish-or-delete offer at startup.
- User-file ops (progress/annotations/collections) mutex-serialized.

## Issues hit en route (logged to avoid repeats)

1. JUnit 4 + Kotlin expression bodies: `fun foo() = runBlocking { ... }` returns the
   last expression → "should be void". Fix: `runBlocking<Unit>`.
2. kotlinx.serialization enums serialize by ENTRY NAME — `@SerialName` per entry is
   mandatory for PWA-lowercase parity (hit in M-2, resolved).
3. DocMeta id/slug/importedAt are required (no defaults) — ImportResult.meta carries
   placeholders; LibraryManager copies over at commit.

## Next session

1. Read `.docs/MOBILE-TRACKING.md` first.
2. M3 import pipeline: detect.ts port (magic bytes) → EPUB importer (DRM detection
   via encryption.xml/rights.xml) → TXT (BOM, Hindi ≥30% Devanagari) → HTML (jsoup
   sanitizer allow-list) → PDF (PDFBox text extraction, ≥50 chars/page heuristic).
   Each importer = pure function bytes→ImportResult, tested against fixtures.
