# AGENTS.md — Folio

Quick orientation for AI agents. The full memory lives in `.docs/` — read it
before writing code, and write back to it as you work.

## What this repo is

- **`mobile/`** is the only product code: a native Android reading app
  (Kotlin + Jetpack Compose + **Readium 3.0.0**), `dev.folio.mobile`, v0.2.0.
- The old PWA monorepo was **deleted** (commit `d793212`); root `PRD.md` and
  `docs/{spec,guide,decisions}.md` are PWA-era background — don't build from them.
- Planned next target: a Tauri 2 + TS/React Windows desktop port, fully specced
  in `desktop-docs/` (with schema-1 data compatibility with mobile).

## Read before substantial work

1. `.docs/README.md` — knowledge-base navigation + mandatory operating rules
   (checkpoint policy, evidence rules, identifier scheme, recovery procedure).
2. `.docs/PROJECT_CONTEXT.md` — stack facts, commands, environment quirks,
   compatibility contracts, what's stale vs. authoritative.
3. `.docs/TASKS.md` + `.docs/CURRENT_SESSION.md` — current board and recovery
   checkpoint (update both when status changes or a milestone completes).
4. Working on `mobile/`? Also read `.docs/MOBILE-TRACKING.md` (authoritative:
   locked plan, decisions 1–11+, session log). Bug/fix sweeps: `.docs/FIX-PLAN-TRACKING.md`.
5. Desktop work: `desktop-docs/README.md`, read in its stated order.

## Verify (run in `mobile/`, all three green = milestone gate)

```bash
./gradlew :app:testDebugUnitTest   # ~340 tests; never trust old green claims — rerun
./gradlew :app:lintDebug           # expect 0 errors (warnings = pre-existing noise)
./gradlew :app:assembleDebug       # then commit; a user-test APK goes at repo root, dated filename
```

Android SDK is VM-local (`mobile/local.properties`, git-ignored, points at
`/tmp/opencode/android-sdk`) — re-provision on any fresh VM before building.
No emulator/device/KVM: device-level checks are the user's job via debug APKs —
report them as blocked, never as passed.

## Hard rules

- Never re-litigate decisions "locked with user" (`.docs/MOBILE-TRACKING.md`
  § Key decisions; `.docs/plans/implementations/dictionary-plan.md`).
- Preserve historical records; append, don't overwrite (`CURRENT_SESSION.md` is
  the one file you rewrite each time — keep it current, not complete history).
- Update `CURRENT_SESSION.md` after every meaningful milestone; never save
  progress only when the usage limit is near. Commit + push after each verified
  state — after every change, per long-standing repo policy — but never claim
  a push/backup succeeded unless you saw it succeed.
- No secrets or keystore material in docs or commits; leave `local.properties`
  ignored. No destructive git ops or remote changes without explicit authorization.
- Schema-1 data contracts (manifest, user JSON, backup ZIP, annotation anchors,
  Drive `drive.file` mirror) must stay byte-compatible across mobile now and the
  desktop port later — see `.docs/PROJECT_CONTEXT.md` → Compatibility contracts.

## Knowledge-base map (only the shortcuts)

| Need | File |
|---|---|
| Recovery / "what do I do next" | `.docs/CURRENT_SESSION.md` |
| Stack, commands, quirks, contracts | `.docs/PROJECT_CONTEXT.md` |
| Tasks & priorities | `.docs/TASKS.md` |
| Session history & next-id (M-25) | `.docs/sessions/README.md`, `.docs/MOBILE-TRACKING.md` |
| Plans & their status | `.docs/plans/README.md` |
| Audits | `.docs/audits/README.md` |
| Decisions index | `.docs/decisions/README.md` |
| Result summaries | `.docs/results/` |
