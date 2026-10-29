# M-25 — S1 Phase 4: library/notes finish + PDF dead-code removal (2026-10-29)

> Executes Phase 4 (final phase) of
> `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`
> (after Phase 3 landed reader recovery: `.docs/results/M25-s1-phase3.md`).

## What was built

1. **PDF code/deps removed (user decision 2).** Deleted
   `core/importer/PdfImporter.kt` (+ its 6-case test file), the
   `pdfbox-android` dependency and its catalog entries, and the
   `PDFBoxResourceLoader.init` from `FolioApplication` (now a plain
   bootstrap). Behavior is honest and unchanged where it matters:
   - `SourceFormat.Pdf` and `pdfZoom` STAY in `Types.kt` — schema-1
     byte-compat obligations (old backups/manifests may carry them).
   - Persistence guards like `LibraryManager`'s `sourceFormat != Pdf`
     reading-edition check stay (they only consume schema history).
   - `DetectedFormat.Pdf` stays in `FileDetector` — a PDF binary is still
     RECOGNIZED by magic bytes, and ImportDispatch rejects it with the
     honest "Unsupported format — EPUB, HTML or TXT only" copy, never
     importing one as a "text article".
   - `BackupZip`'s `.pdf` include-list entry stays: pre-decision backups
     must still restore byte-compatibly.
   - Stale comments corrected (MainActivity parity list, ReaderActivity
     reading-edition note). Net effect: the debug APK shrinks
     42.4 MB → 32.8 MB (pdfbox AAR was ~10 MB dead weight).
2. **Import names from OpenableColumns (audit P2).**
   `uriDisplayName` now queries `OpenableColumns.DISPLAY_NAME` first and
   only falls back to `Uri.lastPathSegment` —providers hand out opaque
   segments (`primary:Download/…`, numeric ids) that previously became the
   book title. Pure mapper `displayNameFrom(queryResult, lastPathSegment)`
   keeps the resolution rules testable without a provider.
3. **Notes header from load state (audit P2).** Pure
   `notesHeaderSubtitle(loading, loadError, count)`: "Loading…" →
   "Saved items unavailable" (load failure) → "Nothing saved yet" / "N
   items" (clean load). The header can no longer claim "Nothing saved yet"
   while the notes file is unreadable or still loading. Visible back/up in
   the Notes TopAppBar (same pattern as Settings, Phase 2); the corrupt
   state keeps its honest body + "Try again" + backup-restore wording.
4. **Selected filter chips beyond color-only.** Notes' custom pills now
   show a leading check icon on the selected pill (plus the existing
   `selectable(selected=…, role=Tab)` a11y state). The library's chips are
   Material3 `FilterChip` — its selected state already renders a lead
   checkmark, so those were compliant.
5. **Import limits/states verified as already landed:** the 50 MB cap is
   enforced pre-read (descriptor size) AND during streaming (`readAtMost`),
   batch import has per-file progress + isolated failures + honest summary
   snackbars (`ImportBatchProgress`/`ImportOutcome`, audit P1-03).
6. **Tests:** +8 (`DisplayNameFallbackTest` 5: display-name wins /
   blank-fallback / null-fallback / blank-everything / directory-only path;
   `NotesHeaderTest` 3: loading-beats-empty, error-never-nothing-saved,
   clean-load counts). −6 (`PdfImporterTest` deleted with the importer);
   `ImportDispatchTest`'s rejection case now feeds raw magic bytes
   (`PdfFixtures` lived in the deleted file). Suite math:
   334 − 6 + 5 + 3 = 336 ✔.

## Verification (this VM)

- **Gates green: 336/336 unit tests, lintDebug 0 errors (57 warnings —
  fewer than Phase 3's 61, same pre-existing noise class), assembleDebug
  green** (32.8 MB debug APK).
- VM note: the default 4 GB Gradle daemon heap OOM-fired on this 7.8 GB
  2-core box mid-run (documented signature); the three gates were re-run
  individually with `-Dorg.gradle.jvmargs="-Xmx2304m
  -XX:MaxMetaspaceSize=640m"` + `-Dorg.gradle.parallel=false`.
- Device journeys NOT run (no device/KVM) — user-side.
- User-test APK at repo root:
  `folio-mobile-debug-2026-10-29-p4-import-notes.apk`
  (sha256 `b5c7929068a8b8a32495cfc421f0ffbd5062a21b3ff167c933ceb335b4f72256`).
  Same uninstall-first rule (locked decision 1): never install over the
  2026-10-27 sync APK.

## S1 audit status after this phase

All four phases (0–4) executed and verified; the audit is CLOSED except
for its device-side checklist (user-side, never claimable here) and the
separate font-packaging/Kotlin-2.1 decision that gates exact CFI restore
(EPUB-010) — its own TASKS.md row, not part of S1.

## Next

User's pick from `.docs/TASKS.md`: Play-side split (M6), CI workflow
restore (`mobile-ci.yml`), font-packaging decision, repo hygiene.
