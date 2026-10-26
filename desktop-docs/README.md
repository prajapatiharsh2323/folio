# Folio Desktop — Documentation

Specifications for building **Folio Desktop for Windows** — a Tauri 2 + TypeScript/React
desktop app with the exact reading experience and UI/UX of the shipped Android app in
`mobile/`.

## Context

- The reference implementation is the **native Android app** (`mobile/`, Kotlin +
  Jetpack Compose + Readium), v0.2.0. The old PWA was deleted; `PRD.md`/`docs/`
  describe the PWA-era design and remain useful background.
- The authoritative UI source is `.docs/design-system.md`.
- Locked build decisions: **Tauri 2 + TS/React · Readium web toolkit reader engine ·
  full schema-1 data compatibility with mobile · feature parity with mobile adds
  nothing extra.**

## Read in order

| # | Doc | Contents |
|---|-----|----------|
| 1 | [01-overview-and-scope.md](01-overview-and-scope.md) | Goals, non-goals, feature parity matrix, desktop rearrangements |
| 2 | [02-architecture-and-stack.md](02-architecture-and-stack.md) | Tauri/TS split, Rust command set, FileStore port, storage tree, import pipeline, tests |
| 3 | [03-ui-ux-spec.md](03-ui-ux-spec.md) | Full token port + desktop adaptations (window, panels, hover, motion, a11y) |
| 4 | [04-reader-spec.md](04-reader-spec.md) | Engine wiring, themes, typography, selection/annotations, progress/resume, pace, performance budget |
| 5 | [05-data-model-and-compat.md](05-data-model-and-compat.md) | Canonical schema-1 TS shapes + invariants + backup ZIP + Drive mirror contracts |
| 6 | [06-features-functional-spec.md](06-features-functional-spec.md) | Library, collections, notes, settings, search, dictionary behavioral specs |
| 7 | [07-keyboard-and-input.md](07-keyboard-and-input.md) | Global/reader/library key maps, mouse/trackpad, window management, file associations |
| 8 | [08-vibe-coding-prompt-pack.md](08-vibe-coding-prompt-pack.md) | Phased, copy-paste prompts (Phase 0 → 11) for the AI coding agent |
| 9 | [09-platform-decisions.md](09-platform-decisions.md) | Drive OAuth, storage/Folder Mode, updates, CI, security guardrails, open items |

## Quick start for the vibe-coding process

1. Read `01` + `02` for the frame.
2. Execute `08` phases sequentially, each prompt citing the doc sections it needs and
   the mobile source files given there.
3. Keep the byte-compatibility rules from `05` inviolable.
