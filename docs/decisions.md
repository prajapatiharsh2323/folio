# Decisions Log

Running log of architectural and technical choices made during the development of Folio, in accordance with Section 1 Rule 7 of `docs/spec.md`.

## Phase 0: Scaffold

- **Monorepo setup**: Configured `pnpm` workspaces for `packages/core`, `apps/web`, and `apps/clipper`.
- **TypeScript**: Strict mode enabled across all packages via `tsconfig.base.json`.
- **OPFS testing environment**: Created `MemoryStore` / mock file system utility for Vitest node environment to test `FileStore` contract without requiring a live browser OPFS instance, while `OpfsStore` directly wraps the standard Origin Private File System (`navigator.storage.getDirectory()`).
- **Icons**: Generated SVG and raster PNG PWA icons for 192x192, 512x512, and maskable 512x512 according to Section 9 specifications.
- **Theme Tokens**: Defined CSS custom properties on `:root[data-theme="..."]` for Light, Warm, and Dark themes matching Section 9 color tokens.

## Phase 1: EPUB Engine Spike Decision

- **Chosen Engine**: `epub.js`.
- **Evaluation Summary**: Tested against English, Hindi (Devanagari conjuncts & matras), and Image-heavy test fixtures.
- **Rationale**:
  1. Full standard EPUB Canonical Fragment Identifier (CFI) support, ensuring location stability across reflow and font size changes.
  2. Built-in annotations and selection ranges API (`rendition.annotations.highlight()`).
  3. Stable TypeScript typings without breaking build pipelines.
  4. Script execution is completely disabled and sandboxed.
