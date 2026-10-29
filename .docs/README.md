# Folio — Development Knowledge Base

`mobile/` is the code; this directory is the memory. Every session is short-lived:
read the entry-point chain below before doing anything, and write back before you end.
Do not move or rename files here without updating the many cross-references by path.

## Start here (in order)

1. **Root `AGENTS.md`** — one-page entry point and operating rules.
2. **`PROJECT_CONTEXT.md`** — what this repo is, verified stack facts, commands, quirks.
3. **`TASKS.md`** — task board with priorities, statuses, blockers.
4. **`CURRENT_SESSION.md`** — recovery checkpoint: last verified state + exact next action.
5. Only then open the deep sources you actually need:
   - `MOBILE-TRACKING.md` — **authoritative** Android context (locked plan, decisions, quirks, session log M-1…M-24).
   - `FIX-PLAN-TRACKING.md` — 2026-10-26/27 fix sweep; row-level statuses with evidence (all rows DONE; 5 rows need a DEVICE pass).
   - `results/` — one summary per major change (M*/R*/L1/B*/D1).
   - `plans/` — feature & implementation plans (D1 dictionary is the most recent).
   - `design-system.md` — authoritative UI tokens.
   - `desktop-docs/` (repo root) — next product target: Tauri 2 + TS/React desktop port.

## Folder map

| Path | Responsibility |
|---|---|
| `README.md` (this) | Navigation + operating rules + recovery procedure |
| `PROJECT_CONTEXT.md` | Architecture, environment, conventions, gotchas |
| `TASKS.md` | Tasks, priorities, blockers, statuses |
| `CURRENT_SESSION.md` | Current checkpoint + exact next action |
| `sessions/` | Session-record index (records live inside the tracking files) |
| `plans/features/`, `plans/implementations/` | Plans and their execution status |
| `audits/bugs/`, `audits/ui-ux/`, `audits/results/` | Audit reports, findings, verification state |
| `decisions/` | Index of decision records and where the locked lists live |
| `improvements/` | Improvement proposals / backlog |
| `archive/` | Superseded / historical notes |

## Operating rules (mandatory)

- Read `AGENTS.md` + this README + `PROJECT_CONTEXT.md` + `TASKS.md` + `CURRENT_SESSION.md` before substantial work.
- Inspect the actual code before modifying it. Docs may be stale; verify claims against source.
- Break complex work into verifiable milestones; verify each one (run the gates, don't assume).
- Update `CURRENT_SESSION.md` after **every meaningful milestone**, not at session end and never only when a usage limit looms.
- Update `TASKS.md` whenever a task's status changes.
- Record decisions, outcomes, test results, blockers, and the exact next action with enough precision that a session with zero memory of this conversation can continue.
- Preserve history: never overwrite or delete existing records. Append session/fix entries; supersede with a pointer instead of erasing.
- Never claim a push/backup happened unless the command actually succeeded. Never commit secrets, keystores, or `local.properties`.
- Identifiers: keep the established scheme — sessions `M-*` (mobile), `D-*` (dictionary), `FIX-*` (fix sweeps); plan classes `M*`/`R*`/`L1`/`B*`/`D1`; defect ids `P0-*`/`P1-*`/`P2-*`. Number new entries up (next mobile session = `M-25`), or use `date-qualifier` names for one-off audits.
- Lifecycle for substantial work: Audit → Findings → Plan → Implementation → Verification → Closure. Track each finding as fixed / unresolved / deferred / rejected / superseded in the file that owns it (fix findings: `FIX-PLAN-TRACKING.md`).

## Recovery procedure (no original conversation available)

1. Read root `AGENTS.md`.
2. Read `.docs/README.md`, `PROJECT_CONTEXT.md`, `TASKS.md`, `CURRENT_SESSION.md`.
3. Inspect Git: `git status && git log --oneline -10 && git branch -a`.
4. Open the plan(s), audit(s), session and result records tagged to the work at hand.
5. Verify the current implementation state yourself (build/tests) before trusting old notes — old notes may predate code changes.
6. Continue from the last verifiable state; don't redo closed work.

## Checkpoint policy (VM-lifetime hazard)

- VMs and sessions are disposable; only committed+pushed work survives.
- Commit + push to `origin/main` after every verifiable milestone (established repo policy, past policy said the same). Never claim success you didn't verify: check `git push` output.
- Do not run destructive git operations, force-pushes, or remote changes without explicit user authorization.
- Backup safety note for agents: automated "backups" that live only inside the VM do not survive the VM. The remote (`github.com/prajapatiharsh2323/folio`) is the real preservation path. Never add secrets to documentation or commits; `mobile/local.properties` and keystores must stay ignored.
