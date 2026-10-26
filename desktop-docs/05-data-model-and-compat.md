# Folio Desktop — Data Model and Compatibility

**Contract:** full schema-1 byte-compatibility with the mobile app
(`mobile/.../core/model/Types.kt`, itself a port of the PWA's `packages/core/src/types.ts`).
JSON field names, optionality, and enum string values **must stay identical**.
Tolerate unknown keys on read; never rename.

---

## 1. Enums (exact strings)

- `DocKind`: `article` | `book`
- `SourceFormat`: `epub` | `pdf` | `html` | `txt` | `clip`
- `AnnotationKind`: `highlight` | `note` | `bookmark`
- `HighlightColor`: `yellow` | `green` | `blue` | `pink`

## 2. Types (TypeScript definition — canonical shape)

```ts
interface Chapter { id: string; title: string; order: number; href: string } // href relative to doc folder

interface DocMeta {
  schema: 1
  id: string            // uuid v4
  slug: string
  title: string
  author?: string | null
  kind: DocKind
  language?: string | null      // BCP 47, e.g. 'en', 'hi'
  sourceFormat: SourceFormat
  sourceUrl?: string | null     // clips and web-origin files
  sourceName?: string | null    // site or publisher name
  capturedAt: string            // ISO-8601 UTC
  importedAt: string            // ISO-8601 UTC
  contentHash: string           // SHA-256 hex of original bytes (clips: of clean/index.html)
  wordCount?: number | null
  searchable?: boolean          // false only for scanned docs w/o text layer
  tags: string[]
  collectionIds: string[]       // ← dual-write with collections.json
  files: {
    original?: string | null    // path inside library/, e.g. "original/book.epub"
    clean?: string | null       // "clean/index.html"
    reading?: string | null     // "reading/book.epub"
    cover?: string | null       // "cover.png"
  }
  chapters: Chapter[]
}

interface ProgressPace { sessionsMs: number; percentRead: number }

interface Progress {
  docId: string
  location: string        // epubcfi(...) or chapter-href fallback
  percent: number         // 0..100, one decimal
  updatedAt: string
  pace?: ProgressPace | null
}

interface Annotation {
  id: string; docId: string; chapterId?: string | null
  quote: string
  prefix: string          // up to 32 chars before quote
  suffix: string          // up to 32 chars after quote
  location?: string | null // engine hint; quote+context authoritative
  kind: AnnotationKind
  color?: HighlightColor | null
  note?: string | null
  createdAt: string; updatedAt: string
}

interface Collection {
  id: string; name: string; docIds: string[]
  createdAt: string
  updatedAt?: string | null   // optional refinement (mobile-added); readers must tolerate
}

interface ManifestFile { path: string; size: number; sha256: string }

interface ManifestDoc {
  id: string; slug: string; title: string
  contentHash: string; updatedAt: string
  files: ManifestFile[]
}

interface Manifest {
  schema: 1
  app: "folio"
  libraryId: string        // uuid v4 — stable per library, used by Drive mirror
  updatedAt: string
  docs: ManifestDoc[]
}
```

User files:

```
library/user/annotations.json  → Annotation[]
library/user/progress.json     → Record<docId, Progress>
library/user/collections.json  → Collection[]
library/user/settings.json     → ReadingPrefs (global)
library/user/doc-settings.json → Record<docId, ReadingPrefs>
```

`ReadingPrefs` — exact shape & defaults in `04-reader-spec.md` §3.

## 3. Serialization rules

- Read with `ignoreUnknownKeys`-equivalent (pass through unknown fields in TS: just
  `JSON.parse` and use only known fields — never delete unknown fields when rewriting,
  byte-compat for future keys).
- Dates always ISO-8601 UTC with `Z`.
- uuids `crypto.randomUUID()`; slugs generated from title lowercase + `-`; collision
  resolved with `-2` suffix (matching mobile Slugify behavior).
- Numbers: `percent` one decimal (`Math.round(x*10)/10`).

## 4. Invariants

1. **Dual-write** `collection.docIds` ↔ `docMeta.collectionIds` (`LibraryManager.setDocCollections` /
   `mutateCollectionDocs`): changes go through one shared mutator.
2. **Manifest = commit point.** Any mutation of doc data ends with an atomic manifest
   rewrite (`updatedAt`, docs list with sizes + sha256 per file). Write order per doc:
   data files first, then rebuild manifest. Manifest last (mobile convention: failures
   leave extra folders → recovery).
3. Deletion of a doc cascades: remove folder, remove from progress/annotations/collections/indexes/drive → then manifest.
4. All writes atomic (tmp → rename; see `02` §4).

## 5. Backup ZIP contract (port of `core/backup/*`)

Structure (mobile-identical):

```
folio-backup/
  backup.json        # {schema:1, createdAt, appVersion, docCount, manifestSha256}
  manifest.json
  docs/<slug>/**     # full doc folders incl. original/reading/clean/cover/meta.json
  user/annotations.json
  user/progress.json
  user/collections.json
  user/settings.json
  (EXCLUDED: user/doc-settings.json, index/, sync/)
```

- `manifestSha256` = sha256 of the raw manifest.json bytes at backup time.
- **Restore = Merge or Replace** dialog first:
  - **Replace**: wipe existing library content (docs/, user/) then restore exactly the
    ZIP; manifest restored as-is.
  - **Merge**: per-doc comparison by contentHash — keep existing doc untouched if hash
    matches; skip duplicates already present; import genuinely new docs; for user files
    (annotations/progress/collections) merge element-wise by id: newest `updatedAt`
    wins; collections merger keeps both and concatenates member lists (intersect
    existing docIds); manifest rebuilt at the end (commit point).
- Restore confirms with summary counts: New docs / Merged docs / Skipped duplicates /
  Annotations merged (same forensic counts as the mobile dialog).
- Export must be streaming (don't hold the library in RAM), and the exported file is
  named `folio-backup-YYYYMMDD-HHmm.zip`.

## 6. Drive mirror contract (port of `core/sync/*`)

- Drive folder: `Folio Library/` in the user's Drive root. Contents mirror
  `library/**` **except** `index/` and the dictionary pack.
- State: `sync/state.json` = `{path → {driveId, sha256}}` for every uploaded path.
- Protocol semantics (mobile `SyncProtocol.kt`):
  1. Reconcile remote files vs. local + state; push locally newer, pull remotely newer.
  2. `manifest.json` uploaded **last**.
  3. Merge is newest-wins per file (`updatedAt` fields in DocMeta/Collection; whole-file
     wins for user JSON lists via per-id newest-`updatedAt` merge, same as backup merge).
  4. **Deletions never propagate**: if a file exists remotely but not locally, pull it
     back; never remote-delete.
  5. Remap docIds: the mirror intentionally allows the same book to exist under two
     docIds (imported separately on mobile and desktop). The merge treats identical
     `contentHash` docs as "same book": first-imported docId wins; progress and
     annotations from the losing id are re-keyed onto the winning id (this is the
     mobile docId-remap rule — port `SyncManager.remapDocs` behavior).
  6. `user/progress.json` merge special case: keep the record with the newest
     `updatedAt` per doc *after* remapping.
- Failure classification (mobile `SyncFailure.kt`): AUTH / NETWORK / CONFLICT_FINAL /
  DISK — surface with matching copy + manual retry; never destructive on failure.
- Auth: see `09-platform-decisions.md` (desktop uses browser-loopback OAuth with
  `drive.file` scope only).
- Auto-sync (opt-in): run while the app is open every 15 minutes (mobile's
  WorkManager 15-min periodic equivalent; skip while user is reading an actively
  changing doc? — no: mobile syncs regardless; keep parity but lock per doc while the
  doc is mid-mutation).
