# Folio — AI Session Tracking

> This file is the persistent context for AI coding sessions. Sessions are short-lived
> (~30–40 min, AI memory erased); read this file FIRST at the start of every session,
> commit + push (origin → `prajapatiharsh2323/folio`, branch `main`) after every
> meaningful change, and update this file before the session ends.
>
> **Working on `mobile/` (native Android)?** Also read `.docs/MOBILE-TRACKING.md` —
> it is the authoritative context file for the Android rewrite (plan, phase ladder,
> contracts, decisions).

## Session log

- **Session 2 (Oct 16, 2026)** — hooks-order crash fix (commit 29fec2e, pushed).
  - Bug: `timeLeftLabel` useMemo was after the error/`!doc` early returns in ReaderScreen →
    hook count changed between null and loaded renders → "Rendered more hooks" crash.
  - Fix: moved the memo above all early returns.
  - Gate added: `eslint-plugin-react-hooks` (`rules-of-hooks: "error"`) for the whole repo —
    this violation is now a lint-RED, would never reach a build again.
  - Note: user added commit "added the samples" (b1b0bd4) between sessions.
  — three-dot card menu clipped (commit c2c6a2f, pushed).
  - Bug: BookCard action popover was positioned inside `.folio-book-card` (`overflow: hidden`) →
    cut off / invisible on phone-sized cards.
  - Fix: popover now `createPortal(document.body)` with fixed coords anchored to the
    three-dot button; `role="menu"` + `menuitem`; Escape/outside-press still close it.
  - New test: `packages/core/tests/book-card-menu.test.tsx` (jsdom + @testing-library/react,
    react/react-dom added as root devDeps) — asserts popover lives outside the card and is a
    direct body child. RED on old markup → GREEN after portal.
  - eslint now ignores `.agents/**` (vendored skill scripts tripped no-unused-vars).
  - Result: lint clean, typecheck clean, suite 80/80.
  — app icon package (design/folio-icon, uncommitted→commit this session).
  - Route: semantic re-author of the project-local mark (origin preserved at
    `folio-icon.origin.svg`). 3 layers: gradient plate, open book, spine+page rules.
  - Verify: clean (no warnings/failures); preview inspected full-size + 64px on
    white/dark/true-black — legible on all three themes.
  - Skill site fix: `.agents/skills/app-icon-gen/scripts/author_icon.mjs` path-depth
    guard used `split("/")` (0 components on Windows); changed to `/[\\/]+/` like the
    neighboring normalizeRelativePath (Windows-compat bug fix).
  — app icon package v2 = ORIGINAL logo (commits 6f5cc80, f947466, 9fe9375, pushed).
  - User clarified brand mark = apps/web/public/icons/logo.png (charcoal two-page
    book #23242F + flat violet bookmark #715CFC), NOT the green-book mark; and
    background removed → transparent deliverable.
  - Built via app-icon-gen: color-segmented logo.png per component (charcoal pages,
    violet bookmark), potrace vectorization, 1024 square canvas; layers
    01-open-book.svg + 02-bookmark.svg; origin evidence folio-icon.origin.png.
  - NOTE: logo.png is NOT transparent — it has a baked opaque white background.
    Fair reference verification required un-matting (alpha recovery vs sampled
    flat colors) + rounded-square mask: mean delta 0.31 (<10), gates OK, 0 warnings.
  - Geometry audit: bookmark clean; book = 11 sub-2px trace-noise segments,
    polish rejected twice (0.4/0.15 tolerance, drift limits) → kept original.
  - Skill/env notes: potrace installed at design/folio-icon/tmp/work/trace (scratch);
    eslint now ignores design/**; verify compares RAW RGBA (references must be
    transparent, not white-matted); verify --width normalizes by width only —
    build square references manually.
  - Restored apps/web/public/icons/icon.svg (researched: to 25f47aa exact bytes).
    Its deletion wasn't ours — ask user where it came from. Manifest references it
    at vite.config.ts:49.
  - Pending: user acceptance of package; optional wiring 192/512/maskable into apps/web.
- **Session 1 (Oct 16, 2026)** — COMPLETE (commits 3a5398e, 26af458, b3ff9e6, 84367a4,
  b1a4bb9, 66c30a3 — all pushed to origin/main).
  - Superpowers plugin installed (opencode.json `plugins` key, git-backed worked).
  - Scope agreed in plan mode: reader ONLY (no clipper); B1 baseline → B2 polish →
    B3 reading stats → B4 themes/fonts → B5 PWA hardening; NO text-to-speech.
  - All 5 tasks complete + fresh-review fix pass. Result summaries in `.docs/results/`.
  - Full details: `.docs/results/B1-baseline.md`, `B2-polish.md`, `B3-B5-results.md`.
- Policy: commit + push after every change; result summaries in `.docs/results/`.

## Environment facts (fresh sessions: re-do these)

- pnpm NOT preinstalled: `npm i -g pnpm` works (corepack enable fails — needs admin).
- Backed versions: pnpm 11.9.0, node 22. Run `pnpm install` if node_modules missing.
- Git identity NOT set on fresh VMs: `git config user.name "Harsh Prajapati";
  git config user.email "harshprajapaticoding@gmail.com"` (repo-local).
- Verification: `pnpm -r run typecheck` · `pnpm run lint` · `pnpm test` (79 passing) ·
  `pnpm --filter @folio/web run build` (PWA: 88 precache entries).
- Completed plan workspace (`.superpowers/sdd/reader-enhancement-plan/`) was cleaned up
  after final review; the durable record lives in git history + `.docs/results/`.

## AI-side deferred minors (candidates for next session — from fresh review 66c30a3)

1. LibraryScreen "Mark as finished" wipes learned pace (`handleToggleFinished` builds a fresh
   Progress — should `getProgress` first and spread).
2. SettingsScreen global theme picker lacks "true-black" (grid-cols-3 → add OLED entry).
3. Quick night toggle in reader header writes hard light/dark, destroying "system"/"warm"
   settings — cycle or remember previous selection instead.
4. `paceState` stays stale when opening a book without prior pace right after one with pace;
   `init()` should always `setPaceState(saved?.pace)` (clear too), not only on presence.
5. Reader `next()/prev()` don't return success — engine change needed so haptics don't buzz
   on no-op turns (first/last page). Touches `ReaderEngine` interface (PdfEngine too).
6. Cosmetic: mis-indented catch comment in epub-engine TOC locations block (~line 670).

## Repo facts (avoid re-discovery)

- Monorepo: `pnpm` workspaces. `packages/core` (logic), `apps/web` (PWA), `apps/clipper`
  (DO NOT TOUCH — verified untouched in session 1).
- Reader: `apps/web/src/screens/ReaderScreen.tsx` (~1950 lines), engines in
  `apps/web/src/reader/` (epub.js + pdfjs behind `ReaderEngine`), `haptics.ts` util.
- Reading prefs: `ReadingPrefs` in `packages/core/src/types.ts`; themes:
  light/warm/dark/**true-black**/system; fontFamily: serif(Literata)/**lora**/sans/mono/publisher.
- Pace/stats: `packages/core/src/reading/stats.ts` (ReadingPace {sessionsMs, percentRead};
  jump threshold 5%; `estimateTimeLeftSeconds`, `formatTimeLeft`). Credit rule: raw delta
  ≤5% forward gets full credit; negative or >5% credit 0. Time: relocation gaps ≤3 min
  credited, larger gaps 0.
- PWA: `apps/web/vite.config.ts` — id "/", launch_handler focus-existing, navigateFallback
  "/index.html" (denylist /share-target), file handlers .epub/.pdf/.html/.htm/.txt.
- Locations cache: `library/cache/locations/<contentHash|id>.json`.
