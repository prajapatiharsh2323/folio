# 2026-10-31 — Repo cleanup & GitHub deployment-readiness

Code unchanged (repo == M-26 commit `9c17568` + this housekeeping commit
`4b7d40b`). All three gates re-verified this VM after a fresh SDK
re-provisioning: 336/336 unit tests, lintDebug 0 errors, assembleDebug green
(30m run. Build executed with the PROJECT_CONTEXT VM flags;
`/home` freed from 99%→93% by cleaning stale `mobile/app/build` outputs first).

## Changed (user-approved plan; matches TASKS.md backlog + improvements 1 & 3)

Deleted from working tree (recoverable from history; no history rewrite):
`PRD.md`, `docs/{spec,guide,decisions,mobile-cloud-sync}.md`, `this.md`,
`.prettierrc`, `design/folio-icon/tmp/` (26 scratch files).

Untracked, kept on disk: repo-root debug APK (2026-10-30, M-26) and
`.agents/` (88 skill files, now gitignored-local).

Rewritten: root `.gitignore` (PWA-era entries out; `/*.apk`, `.agents/`,
OS junk in; `.docs/logs*` keeps), `README.md` (native-Android-forward;
features code-verified — highlights 4 colors, annotations, dictionary,
SHA-256 ZIP backup, search, collections/tags; markdown export was PWA-era
and is NOT claimed), `AGENTS.md` (dead `desktop-docs/` refs → pause state).

Deliberately not added: CI artifact, LICENSE (user's call, 2026-10-31).

## GitHub release

Tag `v0.2.0-m26` →
https://github.com/prajapatiharsh2323/folio/releases/tag/v0.2.0-m26 —
asset `folio-mobile-debug-2026-10-31-m26-settings.apk`
(sha256 `cdef5617eb8f74d4ce8d5e42f1517c3912345db78665b0880613d83c4b27010d`,
same build as M-26 code). Policy now: future APKs ship as release
**assets**, never tracked at repo root (`/*.apk` gitignored).
