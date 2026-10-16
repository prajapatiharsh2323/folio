# Folio — AI Session Tracking

> This file is the persistent context for AI coding sessions. Sessions are short-lived
> (~30–40 min, data erased); read this file FIRST at the start of every session,
> commit + push (origin → `prajapatiharsh2323/folio`, branch `main`) after every
> meaningful change, and update this file before the session ends.

## Session log

- **Session 1 (Oct 16, 2026)**
  - Fetched & followed obra/superpowers `.opencode/INSTALL.md` instructions for OpenCode plugin install.
  - OpenCode is V2 (v2.0.31), so config uses `plugins` key in `~/.config/opencode/opencode.json`.
  - User requirements (reader ONLY — no clipper work):
    1. Advanced PWA that feels native (Android + Windows targets).
    2. Highest-quality reading experience.
    3. All options/features must work correctly.
    4. Advanced, user-friendly UI/UX.
    5. Scope agreed in plan mode: basline verification (B1), native-feel polish + feature
       correctness sweep (B2), reading stats & time-to-finish (B3), extra themes & fonts + quick
       night/brightness access (B4), PWA hardening of manifest/SW (B5).
    6. NO text-to-speech (not selected by user).
  - Policy: commit + push after every minor or major change (short session protection).
  - Policy: result summaries for major changes saved in this folder (`results/` subfolder as
    RESULT files, structured format).
  - Status: B1 in progress (pnpm install running; code audit done).

## Repo facts (avoid re-discovery)

- Monorepo: `pnpm` workspaces. `packages/core` (logic), `apps/web` (PWA), `apps/clipper` (DO NOT TOUCH).
- Stack: TS strict, React 18 + Router v6, Tailwind 3 + CSS var theme tokens, Zustand, epub.js
  (chosen over foliate-js in Phase 1), pdfjs-dist, MiniSearch (worker-based), fflate, DOMPurify,
  vite-plugin-pwa, Vitest + Playwright.
- Reader code: `apps/web/src/screens/ReaderScreen.tsx` (~1900 lines),
  `apps/web/src/reader/{epub-engine.ts,pdf-engine.ts,reader-engine.ts,fonts.ts}`.
- Reading prefs: `ReadingPrefs` in `packages/core/src/types.ts` (theme, flow, fontSize,
  lineHeight, fontFamily, justify, hyphenate, letter-spacing, margin, spread, maxWidth,
  brightness, paragraphSpacing …).
- Store hooks: `useLibraryStore` / `useReaderStore` in `apps/web/src/state/library-store.ts`,
  theme in `apps/web/src/state/settings-store.ts`.
- Locations cache: `library/cache/locations/<contentHash|id>.json` (epub.js locations JSON) —
  useful for stats/words-remaining.
- PRD phases 0–6 all previously accepted; decisions log in `docs/decisions.md`.
- Verification commands: `pnpm --filter @folio/web run typecheck`, `pnpm test` (vitest root),
  `pnpm --filter @folio/web run build`, e2e via playwright (`pnpm` script `e2e`? check
  `playwright.config.ts`).

## Next steps

- [ ] B1: pnpm install done → typecheck → lint → vitest → fix breakage
- [ ] B2: haptics on page turn, transition polish, tap-zone/gesture tuning, feature sweep
- [ ] B3: reading stats + time-to-finish (locations cache pace calc)
- [ ] B4: OLED night theme + extra fonts + quick night/brightness in reader
- [ ] B5: manifest id/display_override/launch_handler/lang, normalize icons, offline
        fallback page, .html/.txt file handlers
- [ ] Superpowers plugin: config written; user must restart OpenCode to load skills
