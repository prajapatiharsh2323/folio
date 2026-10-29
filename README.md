# Folio

> A private, local-first reading library for articles and books.

Folio is an installable PWA for Android and Windows, paired with a browser extension that clips web articles into clean, sanitized, offline-readable copies. Real files are stored directly in your browser's Origin Private File System (OPFS) or a chosen Windows folder, and search is instant, offline, and multilingual (English & Hindi).

---

## Highlights

- 📚 **Read Everything**: Import EPUB, PDF, HTML, and TXT files.
- ✂️ **Web Article Clipper**: Turn noisy web pages into clean, offline EPUB books with one click.
- ⚡ **Instant Offline Search**: Full-text multilingual search in English and Hindi with Devanagari combining mark preservation.
- 🖊️ **Notes & Highlights**: Highlight in 4 colors, add notes with contextual anchoring, and export to Markdown.
- 📦 **Safe Backups**: Streaming ZIP export/import with SHA-256 verification and merge conflict resolution.
- ☁️ **Google Drive Mirror**: Direct zero-server mirror using personal OAuth client ID and `drive.file` scope.
- 🪟 **Windows Folder Mode**: Store your library directly in a folder on your machine (e.g. Google Drive for desktop) with zero API calls.
- 📱 **Native Feel**: 60 fps smooth navigation, Android back gestures, bottom sheets, haptic feedback, and dynamic theming.

---

## Documentation

- 📖 **[User Guide](docs/guide.md)**: Detailed instructions on installation, clipping, reading, shortcuts, backup, and Drive sync.
- 📐 **[Technical Specification](docs/spec.md)**: Full architecture and design requirements.
- 📝 **[Decisions Log](docs/decisions.md)**: Running log of architectural decisions across Phase 0 to Phase 6.

---

## Quick Start

> **Note:** the original PWA/clipper codebase was retired (commit `d793212`) —
> the active product is the native Android app in [`mobile/`](mobile/).
> See [`docs/mobile-cloud-sync.md`](docs/mobile-cloud-sync.md) and
> [`desktop-docs/`](desktop-docs/README.md) (planned Windows desktop port).

```bash
# Android app — verified gates (run inside mobile/)
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug           # lint (0 errors expected)
./gradlew :app:assembleDebug       # debug APK
```

## For AI coding agents

See [`AGENTS.md`](AGENTS.md) and the knowledge base in [`.docs/`](.docs/README.md)
— project context, task board, current session checkpoint, plans, audits, decisions.
