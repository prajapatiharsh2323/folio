# L1 residue — tags + collections (session M-15, Oct 17 2026)

Commit `051c94c`. Wires the existing schema-1 `user/*.json` contracts into the
library UI (PWA parity with `DocumentSheet.tsx` + `LibraryScreen.tsx`).

## Data (`LibraryManager`, PWA `library-manager.ts` ports)

- `createCollection(name)` — fresh id (uuidProvider), empty docIds, now.
- `renameCollection(id, name)` — throws `IllegalArgumentException` on unknown id.
- `deleteCollection(id)` — removes the entry; docs keep stale `collectionIds`
  until reassigned (exactly the PWA behavior).
- `setDocCollections(docId, collectionIds)` — for every collection: add/remove
  the docId per membership; then `updateDocMeta(collectionIds)` (which keeps
  meta.json, the manifest entry name/hash and search text consistent).
- Stored at `library/user/collections.json` (whole-file, pretty JSON, schema-1).

## Pure model (`LibraryUi`)

- `LibraryItem` gains `tags` (blanks filtered) + `collectionIds`.
- `LibraryFilter` sealed: `TagFilter(tag)` / `CollectionFilter(id, name)`.
- `filters(collections, items)` — collections in file order, then unique tags
  A→Z (PWA: `Array.from(new Set(...)).sort()`).
- `applyFilter(items, filter)` — null shows everything.
- `normalizeTag(input, existing)` — PWA DocumentSheet rule: trim + lowercase;
  null when blank or already present.

## UI (`LibraryScreen` + cards)

- **Filter chips**: one horizontally scrollable row (All + collection names +
  `#tags`), exclusive selection, shown only when filters exist; sits under the
  recovery banners. Stale filter (e.g. deleted collection) auto-clears on
  refresh.
- **List**: renders the filtered set; distinct "No documents match this
  filter." empty state (vs the library-empty state).
- **Details dialog** (card overflow menu → Details): tag chips with remove X +
  "Add" input (normalizeTag), collection checkboxes, inline "New collection…"
  + Create (creates and auto-selects). Save applies
  `updateDocMeta(tags, collectionIds)` then `setDocCollections(id, ids)` —
  the PWA write order. Dialog state is per-doc (`remember(item.id)`).
- Not in this slice: rename/delete collections UI (data APIs exist + tested;
  no screen entry point yet) — natural follow-up.

## Tests (+6 → 163 total, 0 failures)

- `LibraryManagerTest`: collection CRUD round-trip + unknown-rename refusal;
  setDocCollections membership sync (A→B switch, empty clears) with meta.json
  read back from disk.
- `LibraryUiTest`: item tags/collectionIds (blanks dropped); applyFilter by
  tag/collection/null; filters() ordering; normalizeTag trim/lowercase/dupes.

## Not done / next

- Reader splash image (cover while loading) — quick win.
- Collections rename/delete UI; tag editing from the reader; R2b time-left.
- Device smoke still blocked (no AVDs/system images on the VM).
