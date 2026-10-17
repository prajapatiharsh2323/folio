# L1 follow-up — collections management (session M-17, Oct 17 2026)

Commit `f8b80a0`. Closes the open item from M-15: `renameCollection` /
`deleteCollection` were tested data APIs with no screen entry point.

## UI

- **Entry point**: top-bar overflow (MoreVert) → "Manage collections".
- **`ManageCollectionsDialog`**: per-row collection name + doc count (from
  `collection.docIds`, kept in sync by `setDocCollections`), rename icon,
  delete icon. Empty state: "No collections yet — add one from a book's
  Details dialog." (creation deliberately stays in the Details flow).
- **Rename**: prefilled `RenameCollectionDialog` sub-dialog; Save disabled for
  blank or unchanged names; trims the input.
- **Delete**: confirm sub-dialog — "\"X\" will be removed. Its documents stay
  in the library." (matches `deleteCollection` semantics: docs keep stale
  `collectionIds` until reassigned — exact PWA parity, unchanged from M-15).
- Both actions: IO write → `refresh()` → snackbar.

## Filter re-binding (pure, tested)

The old refresh guard nulled a filter whenever the `(id, name)` pair vanished.
Rename would therefore drop the selection. `LibraryUi` now has:

- `sameTarget(a, b)` — target identity (collection **id** / tag name), ignoring
  labels.
- `rebind(selected, options)` — re-binds the selection to the refreshed chip
  list: rename keeps the chip selected (fresh object), deleted collection or
  vanished tag drops to All.

`LibraryScreen.refresh()` uses `rebind` instead of the membership check.

## Tests (+1 → 164 total, 0 failures)

`LibraryUiTest.rebind keeps a renamed collection selected and drops vanished
targets` — rename keep, deleted drop, tag match, tag-vanish drop, null stays
null.

## Not done / next

- Device smoke (blocked: no AVD system image on VM), font packaging, R4.
