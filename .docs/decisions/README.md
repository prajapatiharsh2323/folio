# Decisions — Index

Decision prose lives in the source files (avoid duplicating). The rule you most
need: **decisions marked "locked with user" must not be re-litigated without the
user.** New decisions get recorded in the owning file and, if major, linked here.

| Topic | Source | Highlights |
|---|---|---|
| Android locked plan (Route B native rewrite, Readium 3.0.0, MiniSearch-not-FTS5, Play Store, TTS dropped) | `../MOBILE-TRACKING.md` § "Key decisions & rationale" (items 1–11+) | Do not re-litigate |
| PWA-era architecture decisions log (Phase 0–6, historical) | `docs/decisions.md` (repo root) | Background; the PWA code no longer exists at HEAD |
| Dictionary decisions (FreeDict eng-hin, no online fallback, English definitions gap) | `../plans/implementations/dictionary-plan.md` | Locked 2026-10-25 |
| Desktop platform decisions (Tauri 2, Drive OAuth, Folder Mode, updates, CI, guardrails) | `desktop-docs/09-platform-decisions.md` (repo root) | Locked build stack: Tauri 2 + TS/React + Readium web |
| Schema-1 byte-compatibility contracts | `desktop-docs/05-data-model-and-compat.md`, `docs/mobile-cloud-sync.md` | Obligation on every code change |
| One-off technical rulings (timestamps `IsoTime`, corrupt-state preservation, digest contract) | `../FIX-PLAN-TRACKING.md` Fix log + `../PROJECT_CONTEXT.md` | Latest state wins over older docs |

When to write a formal record here instead: cross-cutting choices that outlive
one session and aren't naturally owned by an existing tracker (e.g. "we adopt
Detekt", "release cadence"). Then link it from `TASKS.md`.
