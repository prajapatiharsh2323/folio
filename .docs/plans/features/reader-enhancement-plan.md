# Plan: Folio Reader Enhancement (B1–B5)

Plan file for inline execution via superpowers:executing-plans.
Spec source: PRD.md / docs/spec.md + user's session scope (reader ONLY, no clipper).
User scope decisions: NO text-to-speech; include reading stats/time-to-finish; extra
themes & fonts + quick night/brightness; polish of existing features; PWA hardening.
Policy: commit + push to origin/main after every task/meaningful change (short session
protection). Result summaries → .docs/results/ (structured).

## Global constraints

- TypeScript strict, no new non-`@fontsource` runtime deps without ledger ruling.
- Never touch `apps/clipper/`.
- Every task: verification (typecheck + vitest) before commit; push after commit.
- Follow existing code style (kebab-case files, PascalCase components).
- Reader prefs surface: `ReadingPrefs` (packages/core/src/types.ts) + TypographySheet.

## Task 1 — B1: Baseline verification & repair

Steps:
1. `pnpm install` (installs pnpm globally via npm first if needed).
2. `pnpm --filter @folio/web run typecheck`. Expected: no errors.
3. `pnpm lint` / root lint script (check root package.json). Expected: pass or pre-existing issues ledgered.
4. `pnpm test` (vitest). Expected: all pass.
5. Fix any breakage found (systematic-debugging for failures; each fix RED→GREEN where testable).
6. Commit: `chore: baseline verification fixes (session 1)` → push.

## Task 2 — B2: Native-feel polish

Steps (RED→GREEN where testable):
1. New util `apps/web/src/reader/haptics.ts`: `haptic(pattern: number | number[])` safe wrapper
   with unit test (mock navigator.vibrate; call count + pattern + no-throw when unsupported).
2. Wire haptics: page turns (tap zones + swipe in epub-engine via container event), bookmark
   toggle (replace ad-hoc `navigator.vibrate(10)` with util), highlight save.
3. Chrome show/hide transitions: ensure `motion-reduce` respected (already partially) — verify
   both header/footer; remove any jank (double paint) if found.
4. Tap-zone sweep: paginated tap zones (0–30 / 30–70 / 70–100) and PDF 3-tap logic stay within
   safe insets; document check, fix code only if a bug is confirmed.
5. Feature sweep tick-list (ledger): TOC open/jump, bookmark toggle state, highlight
   add/recolor/delete, notes, in-book search jump, scrubber commit, jump pill, wake lock,
   fullscreen, back-gesture modal close, scrolled-mode next-chapter pill.
6. Commit: `feat(reader): haptic feedback page turns + polish sweep` → push.

## Task 3 — B3: Reading stats & time-to-finish

Steps (TDD in packages/core):
1. RED: unit test `packages/core/tests/reading-stats.test.ts` (or beside src) for
   `estimateTimeLeftSeconds(pace, percentRemaining)` + `updateReadingPace(pace, sessionMs, deltaPercent)`
   with sane clamping (no negative, cap absurd pace).
2. Extend `Progress` type with optional `pace?: { sessionsMs: number; percentRead: number }` —
   backward compatible (schema stays 1).
3. GREEN: implement `packages/core/src/reading/stats.ts`, export from core index.
4. Wire: ReaderScreen accumulates session time since open (relocate → first-relocate baseline),
   updates pace via libraryManager.saveProgress (existing progress write path, merged field).
5. UI: footer tooltip/percent button shows "N min left" when pace known; DocumentSheet shows
   "≈ Xh Ym left · Z% read · pace W min/%" when available.
6. Commit: `feat(reader): reading pace tracking and time-to-finish estimate` → push.

## Task 4 — B4: Extra themes & fonts

Steps:
1. tokens.css: add `data-theme="true-black"` (OLED) token set by cloning dark with #000 bg,
   adjusted surface/border; settings-store THEMES map + resolve; Engine THEME_COLORS map;
   meta theme-color map; library DocumentSheet/Settings theme pickers list update.
2. Fonts: add `@fontsource/lora` (+ Hindi fallback already via Noto Devanagari), register in
   fonts.ts collection + fontMap (engine) + TypographySheet picker ("reader|serif|sans|mono|publisher").
3. Reader header: quick night-mode toggle button (theme cycle light/dark respecting current
   setting) with haptic; aria-labels.
4. Commit: `feat(reader): true-black night theme, reader font expansion, quick night toggle` → push.

## Task 5 — B5: PWA hardening

Steps:
1. vite.config manifest: add `id`, `lang`, `dir`, real `description`, `display_override:
   ["standalone"]`, `launch_handler: { client_mode: "focus-existing" }`; file_handlers add
   `.html`/`.txt` (text/plain, text/html).
2. Icons: inspect apps/web/public/icons; ensure manifest declares accurate sizes; replace
   678x716 odd entry with a proper 512 icon reference (regenerate from logo if trivially
   scriptable — else ledger ruling).
3. Workbox: `navigateFallback: "/index.html"` so deep links work offline; keep share-target SW.
4. Verify build: `pnpm --filter @folio/web run build` succeeds; inspect dist/manifest.webmanifest.
5. Commit: `feat(pwa): manifest hardening, offline navigation fallback, file handlers` → push.

## Review focus (for final reviewer)

- Pace math correctness + persistence merge (no clobber of existing progress fields).
- Theme additions propagate everywhere (documentElement, engine iframe, meta theme-color,
  swipe? none) with no vertical scrollbar/contrast regressions in iframe.
- Manifest changes don't break share-target/File Handling registration.
- No clipper changes.
