# L1 residue — spec §4 Rule 2 recovery (sessions M-13 + M-14, Oct 17 2026)

Both halves of Rule 2 ("a docs/ folder not listed in the manifest is an
interrupted import — offer to finish it or delete it. A manifest entry whose
file is missing is shown as damaged, never silently dropped") are now wired.

- Part 1 (M-13, commit `59422ea`): damaged manifest entries — banner + remove.
- Part 2 (M-14, commit `3f6cae6`): interrupted imports — banner + finish/delete.

## Part 1 — Damaged entries (M-13)

Data side (`LibraryManager.damagedDocuments()`, existing) and the generic
`deleteDocument` needed no changes.

### Pure model (`LibraryUi`)

- `DamagedItem(id, label)` — label = `title` or slug fallback (blank title).
- `DamagedNotice(count, bannerText, items)` — null when nothing is damaged;
  singular/plural banner text ("1 document is damaged — …" /
  "N documents are damaged — …"). JVM-tested.

### UI (`LibraryScreen`)

- `refresh()` loads `damagedDocuments()` → notice state.
- Banner: `errorContainer` card with Warning icon + "Review" button, placed
  above the list (also visible with the empty-library state; hidden while
  search mode is open).
- Review dialog: intro line + one row per damaged doc (label + Remove).
  Remove reuses `deleteDocument` (deletes remaining files, manifest entry,
  progress/annotations/collection memberships — all steps already
  damage-tolerant). Dialog re-binds after each removal and closes itself
  when the last entry is gone.

## Part 2 — Interrupted imports (M-14)

An interrupted import is a `library/docs/<slug>/` folder with no manifest
entry (the manifest is written LAST, so it is the commit point). `finishable`
= its `meta.json` parses (the import got past the meta write; steps after it
— index text — are not required).

### Data (`LibraryManager`)

- `InterruptedImport(slug, title, finishable)` + `scanInterruptedImports()`
  (wraps existing `recoverInterruptedImports()`, adds meta inspection).
- `finishInterruptedImport(slug)`: re-registers the folder into the manifest,
  **rebuilding the file list (path/size/sha256) from disk** via a recursive
  `walkFiles()` over `FileStore.list/exists` (the interface has no recursive
  listing); manifest written last; refuses already-registered slug/id; throws
  when meta.json is missing/unparsable.
- `discardInterruptedImport(slug)`: removes the folder recursively + its
  `library/index/text/<id>.json` when the id is known; **refuses registered
  slugs** (those must go through `deleteDocument`, which also clears
  progress/annotations/collections).
- Hot fact: `AppFileStore.exists()` is file-only (`resolve(p).isFile`) — never
  use it to assert a directory exists; list children or check a file inside.

### UI (`LibraryScreen`)

- Second recovery banner (`RecoveryBanner`, refactored from `DamagedBanner` —
  shared by both notices) above the list; hidden in search mode.
- Dialog rows: label (title-or-slug) + **Finish** (only when finishable) +
  **Delete**; dialog re-binds after each action and closes when clean.
  Snackbars: "Restored “X”" / "Deleted leftover files".

## Tests

- M-13 (+4 → 152): notice text/labels/order; corrupt-meta → remove → clean.
- M-14 (+5 → **157 total, 0 failures**): scan classification
  (finishable vs delete-only); finish flow (manifest entry, hash-verified
  rebuilt files, recovery list empty, `listDocuments` sees it, double-finish
  refused, missing meta refused); discard flow (folder + index text gone,
  registered-slug guard).
- Fixed two dir-existence asserts that used file-only `store.exists()`.

## Not done / next

- Tags/collections UI; reader splash image; R2b time-left remainder.
- Device smoke still blocked (no AVDs/system images on the VM).
