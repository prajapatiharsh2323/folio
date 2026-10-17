# L1 residue — damaged-doc recovery banner (session M-13, Oct 17 2026)

Commit `59422ea`. Damaged manifest entries are surfaced on the library screen
and can be removed, per spec §4 Rule 2 ("shown as damaged, never silently
dropped"). Data side (`LibraryManager.damagedDocuments()`, existing) and the
generic `deleteDocument` needed no changes.

## Pure model (`LibraryUi`)

- `DamagedItem(id, label)` — label = `title` or slug fallback (blank title).
- `DamagedNotice(count, bannerText, items)` — null when nothing is damaged;
  singular/plural banner text ("1 document is damaged — …" /
  "N documents are damaged — …"). JVM-tested.

## UI (`LibraryScreen`)

- `refresh()` also loads `damagedDocuments()` → notice state.
- Banner: `errorContainer` card with Warning icon + "Review" button, placed
  above the list (also visible with the empty-library state; hidden while
  search mode is open).
- Review dialog: intro line + one row per damaged doc (label + Remove).
  Remove reuses `deleteDocument` (deletes remaining files, manifest entry,
  progress/annotations/collection memberships — all steps already
  damage-tolerant). Dialog re-binds after each removal and closes itself
  when the last entry is gone.

## Tests (+4 → 152 total, 0 failures)

- `LibraryUiTest`: null when empty; singular/plural text; label fallback +
  id order preserved.
- `LibraryManagerTest`: corrupt `meta.json` → damaged → `deleteDocument` →
  manifest empty, `damagedDocuments()` empty, doc dir gone (the exact
  banner flow).

## Not done / next

- **Interrupted imports** (spec §4 Rule 2 first half: a `docs/` folder NOT in
  the manifest — "offer to finish or delete"). `recoverInterruptedImports()`
  exists and is tested; no UI yet. "Finish" needs a small data API (re-register
  a valid orphan dir's meta.json into the manifest when parseable).
- Tags/collections UI; reader splash image; R2b time-left remainder.
- Device smoke still blocked (no AVDs/system images on the VM).
