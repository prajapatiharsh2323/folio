# Verification results (audits/results/)

Verification and regression summaries live in **`../../results/`** — one file per
major change (`M*` phase summaries, `R*` reader steps, `L1-*` library items,
`B*` PWA-era, `D1-dictionary.md`) plus project-wide gates recorded in
`../../audits/bugs/FIRST_RELEASE_BUGFIX_REPORT.md` §A and `../../FIX-PLAN-TRACKING.md`
"Fix log" (last verified there: 340/340 unit tests, lint 0 errors, assembleDebug
green, 2026-10-27).

Use this folder only if a future verification round doesn't fit one of the
existing summaries (e.g. a regression sweep that is its own investigation);
name it by date and link it from `../../TASKS.md`.
