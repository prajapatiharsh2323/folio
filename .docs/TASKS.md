# Folio — Task Board

> Legibility layer. Full status + evidence for fix-sweep rows lives in
> `FIX-PLAN-TRACKING.md`; per-phase plan history lives in `MOBILE-TRACKING.md`.
> Update this file whenever a status changes, in the same commit as the change.

P0 = data loss / crash risk · P1 = core UX · P2 = polish. Status: `OPEN`, `IN
PROGRESS`, `BLOCKED`, `DONE`.

## Active / next

| Task | Priority | Status | Notes / source |
|---|---|---|---|
| Settings reduction to app-management + app-theme propagation (user-directed M-26) | P1 (UX) | DONE (M-26) — device checklist user-side | Gated green (336 tests / lint / assemble). Results: `.docs/results/M26-settings-reduction.md`; APK `folio-mobile-debug-2026-10-30-m26-settings.apk`; old root APKs removed (user-authorized) |
| S1 audit — CLOSED (Phases 0–4 DONE 2026-10-29; device checklist user-side) | P0 (UX) | DONE (M-25) | `.docs/audits/ui-ux/S1-SETTINGS-SYNC-UX-AUDIT.md`; results: `.docs/results/M25-s1-phase0.md` … `-phase4.md`; Phase 3 APK `folio-mobile-debug-2026-10-29-p3-reader-recovery.apk`, Phase 4 `folio-mobile-debug-2026-10-29-p4-import-notes.apk` |
| S1 Phase 1 pre-step: user uninstalls the 2026-10-27 sync-enabled APK before installing the sync-free build (one-step decision) | P0 | READY (user-side) — sync-free APK at repo root | S1 audit locked decision 1 — never install over the old APK; install `folio-mobile-debug-2026-10-29-sync-free.apk` |
| Re-provision Android SDK (`/tmp/opencode/android-sdk`) + rerun 3 gates to re-verify HEAD in this VM | P1 | DONE (M-25) | SDK 35 + build-tools 35.0.0 provisioned; HEAD `9058c8e` gates green (test/lint/assemble) |
| User manual-testing round for the 2026-10-27 debug APK (15 device journeys, TalkBack, real Drive sync) | P1 | SUPERSEDED | Sync journeys drop (sync removed); the rest moves to the S1 Phase-1 device checklist `audits/bugs/FIRST_RELEASE_BUGFIX_REPORT.md` §E |
| 5 fix-sweep rows flagged **DONE (DEVICE)** (pillar slider a11y, appearance-sheet preview persistence, theme-row narrow-width, reader chrome, sync worker attention UI) — need a device/TalkBack pass | P2 | BLOCKED (device) | `FIX-PLAN-TRACKING.md` rows marked `DONE (DEVICE)`; "sync worker attention UI" row becomes moot with sync removal |
| Restore CI: `.github/workflows/mobile-ci.yml` was deleted with the PWA purge (`d793212`); history has the original (JDK 17 temurin, unit tests + lint + assemble, report artifacts) | P1 | OPEN | Recreate, ideally `mobile/` paths-filtered. Was mobile-ci (M-20, commit `423f0cf`). |
| Font packaging decision (own session): bundle ttf @font-face for Devanagari/Literata *or* upgrade Kotlin to 2.1.x to unlock Readium 3.1.2 `FontFamilyDeclaration` | P2 | OPEN | `MOBILE-TRACKING.md` "NEXT SESSION" §3; upgrade also unlocks exact CFI restore (EPUB-010) + in-page search-hit polish (EPUB-015) — S1 Phase 3 reader work depends on this choice |

## Next-ladder (from the locked plan)

| Task | Priority | Status | Notes / source |
|---|---|---|---|
| R4 — footnote popups + media overlays (TTS stays dropped) | P2 | OPEN | `MOBILE-TRACKING.md` phase ladder |
| M6 — Play hardening: signing, AAB, R8, baseline profile, Data Safety, in-app updates | P2 | OPEN (partials landed: `allowBackup=false`, data-extraction-rules M-20) | phase ladder |
| Desktop port (Tauri 2) | P1 (next product) | PAUSED — user deleted `desktop-docs/` (commit `9058c8e`, 2026-10-29); the specs remain recoverable from git history | `desktop-docs/` (deleted on disk, in history) |
| Batch 2 reader polish: paragraph-snap scrolling (check Readium reflowable behavior on device first) | P2 | OPEN | `MOBILE-TRACKING.md` "NEXT SESSION" §2 |

## Deferred (accepted, not debt)

- TTS read-aloud; fixed-layout EPUB; custom user CSS; auto-scroll; repair-import; landmarks/page-list — `MOBILE-TRACKING.md`.
- WordNet English-definitions gap for the dictionary — `plans/implementations/dictionary-plan.md`.
- PWA-era "AI-side deferred minors" (pace handling, theme-line grid, night toggle semantics — items 1–6 in `SESSION-TRACKING.md`) described code that was deleted; treat them as historical candidates only — re-derive against `mobile/` before acting.

## Repo hygiene (optional, user's call)

- ~~2 debug APKs (~79 MB) tracked at repo root~~ DONE 2026-10-30 (M-26,
  explicit user authorization): all older APKs removed; a single dated
  user-test APK remains at the repo root.

  > Done + moved forward 2026-10-31: that APK is untracked; APKs now ship
  > as GitHub Release assets (root `/*.apk` gitignored).
- `this.md` (15-byte stub) and PWA-era `.prettierrc`/`.gitignore` entries are leftovers.

  > DONE 2026-10-31 (user-approved cleanup): `this.md`, `.prettierrc`,
  > `PRD.md`, `docs/{spec,guide,decisions,mobile-cloud-sync}.md`,
  > `design/folio-icon/tmp/` deleted; root `.gitignore` rewritten;
  > `.agents/` untracked (kept local). 2026-10-30 M-26 APK untracked →
  > shipped via GitHub Release `v0.2.0-m26` (dated 2026-10-31 asset).
  > No CI artifact / LICENSE by user's call.
- S1 was crossed with this earlier note (kept for history): `mobile/auditscratch/`
  is untracked scratch (`audit-build.log`, one-off validation test).
