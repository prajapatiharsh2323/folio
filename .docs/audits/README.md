# Audits — Index

Lifecycle: Audit → Findings → Plan → Implementation → Verification → Closure.
Keep the original report; record disposition (fixed / unresolved / deferred /
rejected / superseded) in the file that tracks fixes — don't rewrite audits.

| Report | Location | Disposition tracker |
|---|---|---|
| EPUB reader audit (46 main + 28 test sources, static review) | `ui-ux/EPUB_READER_AUDIT_REPORT.md` (restored from git history `d793212^` — deleted by accident with the PWA purge) | Fixed in M-20/M-21 batches (`MOBILE-TRACKING.md`), residual DEVICE rows in `FIX-PLAN-TRACKING.md` |
| UX/UI & Reading-Experience audit (2026-10-26) | Original audit doc is in git history; live disposition = `../FIX-PLAN-TRACKING.md` "Source plan" header | `../FIX-PLAN-TRACKING.md` rows |
| First stable release bug-fix pass (2026-10-27, FIX-E, conditional GO) | `bugs/FIRST_RELEASE_BUGFIX_REPORT.md` | §E blockers tracked in `../TASKS.md` |

Rule of evidence: every fix row must name the file or test that proves it
(`FIX-PLAN-TRACKING.md` header). Never mark a row DONE on prose alone.
