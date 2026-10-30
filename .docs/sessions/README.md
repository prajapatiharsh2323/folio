# Session Records — Index

Full session narratives live inside the tracking files (kept there to avoid
duplicates and because they carry evidence). This index locates them.

## Where sessions are recorded

- **Android sessions M-1 → M-24** — `.docs/MOBILE-TRACKING.md` (Status line +
  session headings/fix entries throughout; sessions M-20/M-21 = EPUB-audit
  remediation; D-1/D-1b = dictionary; FIX-A…FIX-E = 2026-10-26/27 fix sweep at
  the bottom of `FIX-PLAN-TRACKING.md`).
- **PWA-era sessions 1–3** — `.docs/SESSION-TRACKING.md` § Session log
  (historic; env commands stale, see `../PROJECT_CONTEXT.md`).

## Timeline summary (grep anchors)

| Span | Sessions | Theme |
|---|---|---|
| Oct 12–16 2026 (PWA) | 1–2 | PWA reader enhancement, icon package, hooks-order crash fix, card-menu portal fix |
| Oct 20 2026 (PWA) | 3 | Android UX/cloud/notes/settings pass (branch `ux/cloud-notes-settings`, merged as `da1864a`) — overlaps M-24 |
| Oct 17–19 2026 | M-1 → M-19 | Android scaffold, storage, import pipeline, reader chapters, library, search |
| Oct 19 2026 | M-20/M-21 | EPUB audit remediation batches (see `../audits/ui-ux/EPUB_READER_AUDIT_REPORT.md`) |
| Oct 20 2026 | M-22 → M-24 | collections trace, icon tint, cloud/notes/settings UX |
| Oct 25 2026 | D-1, D-1b | dictionary build + repair (`../plans/implementations/`) |
| Oct 26 2026 | FIX-A…FIX-D | UX/UI & data-safety fix sweep (sheet top at `../FIX-PLAN-TRACKING.md`) |
| Oct 27 2026 | FIX-E + first-release report | release hardening; verdict in `../audits/bugs/FIRST_RELEASE_BUGFIX_REPORT.md` |
| Oct 29 2026 (user) + M-25 | M-25 | S1 audit adoption + full close: Phase 0 data-safety/release gates, Phase 1 Drive-sync removal (offline-first), Phase 2 Settings rebuild, Phase 3 reader recovery & comfort, Phase 4 library/notes finish + PDF removal — results `../results/M25-s1-phase0..4.md` |
| Oct 30 2026 | M-26 | User-directed Settings reduction (app-management only) + app-level theme propagation (`FolioAppTheme`), reader-sheet global-defaults action, root APK cleanup — result `../results/M26-settings-reduction.md` |

## Rules for new session records

Next mobile session number: **M-27** (then increment). Record the session inside
`MOBILE-TRACKING.md` (append, don't overwrite) if it's project work; if it's a
standalone audit or one-off investigation, create a dated file in the relevant
`audits/`, `plans/`, or `archive/` folder and link it from this index and from
`TASKS.md`/`CURRENT_SESSION.md`.
