# D1 — Dictionary (offline English→Hindi) — result summary

**Status:** IN PROGRESS (session D-1, 2026-10-25)
**Plan:** `.docs/plans/dictionary-plan.md` — read that first after memory erase.

## Progress checklist

- [x] Repo inspected; facts verified (Readium 3.0.0, custom selection bar,
      no DB in project, tokens identified).
- [x] Plan + tracking committed.
- [ ] FreeDict eng-hin 1.6 source downloaded + licence verified.
- [ ] Converter script + assets pack built.
- [ ] Core dictionary layer (pure Kotlin + sqlite).
- [ ] DictionarySheet UI + ReaderActivity wiring.
- [ ] Unit tests green.
- [ ] Report `FOLIO_DICTIONARY_IMPLEMENTATION_REPORT.md`.
- [ ] Tracking updated (MOBILE-TRACKING.md status line + session log).

## Outcomes / notes

(filled as work completes)

## Blocked / deferred

- English definitions: FreeDict eng-hin has none → Open English WordNet
  (CC BY 4.0) add-on deferred to a later session (user approved this gap).
- Online fallback: deferred per user decision (interface stub only).
- Device validation: no emulator/SDK in this sandbox → user manual round.
## Phase 2 — data pack (DONE 2026-10-25)

- Verified download `freedict-eng-hin-1.6.dictd.tar.xz`, sha512
  `6fa888f3f88db256b838ba31d78de9d6fc46c8979c5b32b91ccd0a7c89a16041811012470de143619ee88b2e7366932a0d7863e53089848ea3db8e130b4962b0` (matches publish).
- Source inspected: `freedict-eng-hin-1.6.src.tar.xz` → `eng-hin.tei` (12.5 MB,
  25,642 `<entry>`), COPYING = **GPL v2**, README notes data from **Anusaaraka
  English-Hindi Dictionary V2.0 (IIIT Hyderabad)**, quality "low", built by
  school children; maintainer Michael Bunk.
- Coverage measured: pos on 100% of entries (N 13906, Adj 5419, V 2539, VT 1426,
  Adv 1019, VI 259…), 2355 multiword headwords, 61 apostrophe, 836 hyphen,
  ~24.7k entries carry example sentences; **0 pronunciations**; no English
  definitions (as predicted).
- Dataset quirks handled key-side: "well-being" stored as "well being" →
  `headword_relaxed` secondary key (hyphen/space/apostrophe-agnostic);
  enclosing quotes/brackets stripped; `~` connector → space (notation of the
  dataset); "?"-only translations dropped.
- Converter: `mobile/tools/build_dict.py` (stdlib only). Output
  `mobile/app/src/main/assets/dictionary/eng-hin.db` —
  **5.0 MB, 25,450 rows** (192 skipped: unparseable/blank), sha256
  `f94e84cebede613704c2e6e37a1b0a66292263461ead1be06069f4bc5ab113e8`,
  meta table with schema_version=1.
- Licence text shipped as
  `mobile/app/src/main/assets/dictionary/eng-hin-COPYING.txt` (GPL v2 verbatim).
- Spot checks: run → 2 POS rows; well-being → relaxed hit; "give up" → honest
  no-match (phrase absent from dataset).
