# Archive — Superseded & Historical

- The PWA monorepo (`apps/web`, `apps/clipper`, `packages/core`, pnpm workspace)
  was deleted at commit `d793212` (2026-10-24, "delete the pwa section and keep
  only mobile"). Its code and docs are fully recoverable from git history:
  e.g. `git show d793212^:apps/web/src/screens/ReaderScreen.tsx`.
- `EPUB_READER_AUDIT_REPORT.md` was accidentally caught by that purge (it audits
  `mobile/`) and has been restored to `../audits/ui-ux/` from `d793212^`.
- PWA-era session log: `../SESSION-TRACKING.md` § Session log + deferred minors.
  Treat its environment notes as stale (pnpm gates no longer exist anywhere).
- `docs/spec.md`, `docs/guide.md`, `docs/decisions.md`, `PRD.md` (repo root):
  kept as product background — see `../PROJECT_CONTEXT.md` "Where the truth lives".
