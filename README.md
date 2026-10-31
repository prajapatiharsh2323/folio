# Folio

> A private, local-first reading library for Android.

Folio is a native Android reading app — your books and articles stay **on your
device**: no accounts, no servers, no tracking. Import EPUB, PDF, HTML, or TXT
files, read them in a fast, comfortable reader, and keep everything organized
with collections, tags, notes, and highlights.

**Package:** `dev.folio.mobile` · **Version:** v0.2.0 · **Stack:** Kotlin + Jetpack Compose + [Readium 3](https://readium.org/)

---

## Features

- 📚 **Multi-format library** — EPUB, PDF, HTML, and TXT import via system file picker or open-with.
- 📖 **Full-featured reader** — paged and scrollable modes, adjustable typography and comfort settings, reader splash with cover art.
- 🖊️ **Notes & highlights** — highlight in 4 colors, add notes and annotations anchored to text selections, quote cards.
- 🗂️ **Collections & tags** — group, filter, rename, delete; real list UI with progress and sorting.
- 🔤 **Built-in dictionary** — look up words while you read.
- 💾 **Backups** — streaming ZIP export/import with SHA-256 verification and merge conflict resolution.
- 🛡️ **Local-first** — everything stored on-device; works fully offline.

---

## Install

Grab the latest APK from
[**GitHub Releases**](https://github.com/prajapatiharsh2323/folio/releases),
allow "install from unknown sources" on first run, and you're set.

---

## Building from source

Requires JDK 17 and the Android SDK.

```bash
cd mobile/
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug           # lint (0 errors expected)
./gradlew :app:assembleDebug       # debug APK
```

All three gates green is the milestone standard — debug APKs are built for
user testing on real devices.

## Repo map

| Path | What it is |
|---|---|
| `mobile/` | The product — native Android app (Kotlin + Compose + Readium 3) |
| `design/folio-icon/` | Canonical app icon sources and final assets |
| `cover-imgs/` | Bundled Folio cover-art set |
| `folio-logo.png` | Canonical launcher-icon source |
| `.docs/` | Development knowledge base (plans, audits, session records) — aimed at AI coding agents, readable by humans too |

## For AI coding agents

See [`AGENTS.md`](AGENTS.md) and the knowledge base in
[`.docs/README.md`](.docs/README.md) — project context, task board, current
session checkpoint, plans, audits, and decision records.
