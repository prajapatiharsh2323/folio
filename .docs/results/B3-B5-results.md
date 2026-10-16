# RESULT — B2–B5 + review fixes (Session 1, continuation commits)

| Task | Commit | Verification | Summary |
|---|---|---|---|
| B1 baseline | 3a5398e | tsc PASS · eslint 0 err (was 17) · vitest 64/64 | See B1-baseline.md |
| B2 polish | 26af458 | tsc · eslint · vitest 69/69 | See B2-polish.md |
| B3 stats | b3ff9e6 | tsc · eslint · vitest 78/78 | ReadingPace in core (9 tests), wired to relocations + progress persistence; footer "N min left" (md+) + DocumentSheet progress row |
| B4 themes/fonts | 84367a4 | tsc · eslint · vitest 78/78 | true-black (OLED) theme in tokens/settings/epub/pdf engines; resolved reader root data-theme (fixed `system` never resolving in reader); Lora font (picker, stacks, iframe @font-face); quick night toggle |
| B5 PWA | b1a4bb9 | production build PASS | id/lang/dir/description/display_override/launch_handler focus-existing; standard icons; navigateFallback (SPA offline deep links, share-target excluded); .html/.txt handlers. dist/manifest.webmanifest inspected |
| Review fixes | 66c30a3 | vitest 79/79 · eslint | Fresh branch review → 3 Important findings fixed (see below) |

## Final review (fresh reviewer subagent) — findings & dispositions

**Fixed (Important, TDD):**
1. OLED theme made highlights/search matches near-invisible (multiply blend on #000 JSON).
   → true-black now treated as dark (screen blend, 0.65 opacity).
2. Pace under-credited deep reading (30 s/relocation cap) → inflated time-left ~8×.
   → no per-relocation cap; gaps >3 min credit 0 (away-time washout).
3. Repeatable scrub yoyo inflated percentRead (+2 per cycle) → deltas >5% now credit 0%;
   regression test added for the yoyo loop.

**Declined-then-ruled / deferred minors (NOT fixed this session):**
- Ruling (manifest id): `id: "/"` changes installed-app identity once; data is origin-scoped
  (OPFS) so libraries survive. One-way choice, accepted intentionally — cost: existing
  home-screen installs re-pin to the new identity.
- Deferred (minor): "Mark as finished" in LibraryScreen rebuilds Progress without existing pace
  → learns pace lost on that tap.
- Deferred (minor): global SettingsScreen theme picker lacks true-black (picked in reader but
  invisible/unselected in Settings).
- Deferred (minor): quick night toggle writes hard light/dark, destroying "system"/"warm" prefs.
- Deferred (minor): paceState stale when opening a book without prior pace right after one with
  pace (clears on first relocation).
- Deferred (minor): haptics buzz even on no-op turns (first/last page) — engine would need
  boolean-returning next/prev.
- Deferred (minor): cosmetic mis-indent of one catch comment in epub-engine TOC block.
- Reviewer "declined to judge": fixture EPUB binaries rewritten in every commit (pre-test
  fixture generation, no user impact); pre-existing chunk>500 kB build warning.
