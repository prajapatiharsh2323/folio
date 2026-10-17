# L1 residue — EPUB covers slice (session M-12, Oct 17 2026)

Commit `fb2cb20`. Importer extracts EPUB covers, library cards show them.
PWA-side code is untouched (mobile-first feature; the schema-1 contract
already reserved `meta.files.cover`).

## Contract (matches `docs/spec.md` layout section)

- File: `library/docs/<slug>/cover.<ext>` — extension follows the source
  image (`cover.jpg`, `cover.png`, …); `cover.jpg` is just the common case.
- `meta.files.cover` = doc-dir-relative name (e.g. `cover.jpg`) — same
  convention as `original/…`, `clean/index.html`, `reading/book.epub`.
- Manifest: cover recorded in `manifestFiles` with sha256 like every other
  content file; delete cascade needs no special-casing (whole doc dir is
  removed recursively).
- Write order unchanged in spirit: content (cover is content) → meta.json →
  search text → manifest LAST (commit point).

## Detection chain (EpubImporter)

1. `<item properties="cover-image">` (EPUB3; token match, case-insensitive)
2. `<meta name="cover" content="id"/>` (EPUB2; id resolved via manifest map,
   attribute order irrelevant — regex searches the whole tag)
3. Heuristic: image item whose id **or** href contains "cover"

Candidates are tried in order and the first one that actually loads bytes
from the zip wins (missing zip entry falls through to the next). Image
classification = `media-type` starts with `image/` OR href has a known
image extension (`jpg/jpeg/png/gif/webp`). Zip lookup chain mirrors the
chapter reader (exact → opfDir-prefixed → raw href → URL-decoded →
case-insensitive).

## Card display

- `LibraryUi.LibraryItem.cover` = store-relative path
  (`library/docs/<slug>/cover.jpg`) or null; pure + JVM-tested.
- `CoverLoader` (`core/library/CoverLoader.kt`):
  - `sampleSize(srcW, srcH, reqW, reqH)` — pure power-of-two math: halves
    while both dimensions still cover the request; never upscales;
    non-positive input → 1 (JVM-tested).
  - `decode(store, path)` — bounds pass (`inJustDecodeBounds`) → sampled
    decode at 132×174 px (44×58 dp card thumb @ 3x) → 8 MB `LruCache`
    keyed by store path. Blocking; called under `Dispatchers.IO`.
- `LibraryCard`: 44×58 dp `ContentScale.Crop` thumbnail when the cover
  loads; the old 6 dp tinted spine bar remains the fallback (and the
  loading state). No layout jump: both occupy the same slot.

## Tests (+11 → 148 total, 0 failures)

- `EpubImporterTest`: properties cover, source ext (png), EPUB2 meta name,
  heuristic-by-id, no-cover → null.
- `EpubFixtures.epubWithCover(...)`: one-chapter EPUB with configurable
  cover item built in-memory with `java.util.zip` — shared PWA fixture
  `english-novel.epub` is NOT touched (byte golden for both suites).
- `LibraryManagerTest`: cover written + hashed + recorded in
  `manifestFiles`; on-disk `meta.json` carries `files.cover` (decoded back
  and asserted — PWA-compatible); no-cover result → `files.cover` null.
- `LibraryUiTest`: path join against slug; blank/absent → null.
- `CoverLoaderTest`: sample-size halving, no-upscale, non-positive input.

## Not done / next

- Damaged-doc recovery banner (uses `LibraryManager.damagedDocuments()`).
- Tags/collections UI; reader splash image; R2b time-left remainder.
- Device smoke still blocked (no AVDs/system images on the VM).
