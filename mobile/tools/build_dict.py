#!/usr/bin/env python3
"""
build_dict.py — convert a FreeDict TEI dictionary into Folio's offline
SQLite dictionary pack.

Currently targets FreeDict eng-hin (release 1.6, GPL v2, data originally
from the Anusaaraka English-Hindi Dictionary V2.0, IIIT Hyderabad).
stdlib only: xml.etree, sqlite3, hashlib, argparse.

Notation handling (documented, honest):
- "~" inside Hindi quotes is this dataset's morpheme connector
  (e.g. "लम्बा~दौर"); replaced with a single space for display.
- Headwords carrying enclosing quotes ("give") or bracket suffixes
  ("wellington[boot]") are cleaned for display; the lookup key strips
  quotes, brackets, and unifies hyphen/space spelling (this dataset
  stores "well-being" as "well being").
- Question-mark-only translations ("?") count as missing and are dropped.
- Examples only when the source provides one; never fabricated here.
- Pronunciations: this dataset has none — column stays empty.

Usage:
  build (needs the TEI; kept out of the repo for licence hygiene):
    python3 build_dict.py --tei /path/eng-hin.tei --out /path/eng-hin.db \
        --name "FreeDict eng-hin" --version "1.6" \
        --licence "GPL-2.0" \
        --attribution "FreeDict eng-hin; data: Anusaaraka English-Hindi Dictionary V2.0 (IIIT Hyderabad); maintainer: Michael Bunk"
    The script prints the pack's FILE sha256 next to a
    DictionaryRuntime.PACK_SHA256 reminder — that constant is the digest of
    the shipped asset bytes (also written to <out>.sha256), and the pack
    self-describes its row integrity through meta.content_sha256 (a file
    cannot contain its own byte digest).

  repair an already-built pack's meta (no TEI needed):
    python3 build_dict.py --upgrade /path/eng-hin.db
"""

import argparse
import hashlib
import json
import re
import sqlite3
import sys
import unicodedata
import xml.etree.ElementTree as ET

DOTD_RE = re.compile(r"<!DOCTYPE.*?\]\s*>", re.S)
WS_RE = re.compile(r"\s+")
KEY_STRIP_RE = re.compile(r'^["\')(\[\]]*[$")\]\[]*')


def local(tag):
    return tag.rsplit("}", 1)[-1] if "}" in tag else tag


def child_texts(el, path):
    """All text of elements matching path 'a/b/c' under el, whitespace-collapsed."""
    parts = path.split("/")
    out = []

    def walk(node, depth):
        for c in list(node):
            if depth == len(parts) - 1 and local(c.tag) == parts[depth]:
                t = WS_RE.sub(" ", (c.text or "")).strip()
                if t:
                    out.append(t)
            else:
                walk(c, depth + 1)

    walk(el, 0)
    return out


def first_text(el, path):
    ts = child_texts(el, path)
    return ts[0] if ts else None


def clean_headword(raw):
    """Strip enclosing punctuation/bracket noise this dataset writes into orths."""
    hw = raw.strip()
    hw = re.sub(r"\[.*?\]", "", hw)          # wellington[boot] -> wellington
    hw = re.sub(r"^['\"]+", "", hw)          # "give -> give
    hw = re.sub(r"['\"]+$", "", hw)
    hw = re.sub(r"^[-.,;:]+|[-.,;:]+$", "", hw)
    hw = WS_RE.sub(" ", hw).strip()
    return hw


def key_norm(hw):
    """Primary key: NFKC, casefold, punctuation-stripped, single-space."""
    s = unicodedata.normalize("NFKC", hw)
    s = s.casefold()                          # ASCII data; casefold ≈ Locale.ROOT
    s = WS_RE.sub(" ", s).strip()
    return s


def key_relaxed(s):
    """Secondary key: hyphen/space/apostrophe agnostic (cross-orthography)."""
    s = key_norm(s)
    return s.translate(str.maketrans("", "", " '-"))


def parse_senses(entry):
    """Return [{"hindi": [..], "example": str?}, ...] in source order."""
    senses = []
    for sense in list(entry):
        if local(sense.tag) != "sense":
            continue
        hindi, example = [], None
        for cit in list(sense):
            if local(cit.tag) != "cit":
                continue
            ctype = cit.get("type") or ""
            for q in list(cit):
                if local(q.tag) != "quote":
                    continue
                t = WS_RE.sub(" ", (q.text or "").replace("~", " ")).strip()
                if not t or t == "?":
                    continue
                if ctype == "trans" and t not in hindi:
                    hindi.append(t)
                elif ctype == "example" and example is None:
                    example = t
        if hindi or example:
            senses.append({"hindi": hindi, "example": example})
    return senses


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tei", help="FreeDict TEI XML source (for a fresh build)")
    ap.add_argument(
        "--upgrade",
        metavar="PACK",
        help="repair an existing pack's meta (no TEI needed): drop the "
             "circular legacy `sha256` key, add `content_sha256` over rows, "
             "write the file-digest sidecar",
    )
    ap.add_argument("--out", help="output pack path (requires --tei)")
    ap.add_argument("--name", help="dataset name")
    ap.add_argument("--version", help="dataset version")
    ap.add_argument("--licence", help="licence id (e.g. GPL-2.0)")
    ap.add_argument("--attribution", help="attribution line")
    ap.add_argument("--schema-version", default="1")
    args = ap.parse_args()

    if args.upgrade:
        return upgrade(args.upgrade)

    raw = open(args.tei, encoding="utf-8").read()
    raw = DOTD_RE.sub("", raw, count=1)
    root = ET.fromstring(raw)

    entries = [el for el in root.iter() if local(el.tag) == "entry"]
    n_src = len(entries)

    conn = sqlite3.connect(args.out)
    cur = conn.cursor()
    cur.executescript(
        """
        BEGIN;
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE dict(
          id INTEGER PRIMARY KEY,
          headword TEXT NOT NULL,
          headword_norm TEXT NOT NULL,
          headword_relaxed TEXT NOT NULL,   -- hyphen/space/apostrophe-agnostic
                                            -- key (dataset stores "well-being"
                                            -- as "well being")
          pos TEXT,
          senses_json TEXT NOT NULL         -- [{hindi:[..], example?}] source order
        );
        CREATE INDEX dict_norm ON dict(headword_norm);
        CREATE INDEX dict_relaxed ON dict(headword_relaxed);
        COMMIT;
        """
    )
    cur.execute("PRAGMA journal_mode=DELETE")

    seen = set()
    n_rows = n_skipped = 0
    for e in entries:
        hw = clean_headword(first_text(e, "form/orth") or "")
        pos = first_text(e, "gramGrp/pos")
        if not hw:
            n_skipped += 1
            continue
        norm = key_norm(hw)
        relaxed = key_relaxed(hw)
        if not norm:
            n_skipped += 1
            continue
        senses = parse_senses(e)
        if not senses:
            n_skipped += 1
            continue
        dedup = (norm, pos)
        if dedup in seen:
            n_skipped += 1
            continue
        seen.add(dedup)
        cur.execute(
            "INSERT INTO dict(headword, headword_norm, headword_relaxed, pos, senses_json) VALUES (?,?,?,?,?)",
            (hw, norm, relaxed, pos, json.dumps(senses, ensure_ascii=False, separators=(",", ":"))),
        )
        n_rows += 1

    sha = hashlib.sha256(content_digest_out(args.out).encode("utf-8")).hexdigest()
    meta = {
        "schema_version": args.schema_version,
        "dataset": args.name,
        "dataset_version": args.version,
        "content_sha256": sha,
        "attribution": args.attribution,
        "licence": args.licence,
        "source_entries": str(n_src),
        "stored_rows": str(n_rows),
    }
    for k, v in meta.items():
        cur.execute("INSERT INTO meta(key, value) VALUES (?,?)", (k, v))
    conn.commit()
    conn.close()
    finalize(args.out, meta, n_rows, n_skipped)
    return 0


# ---------------------------------------------------------------------------
# Pack finalization (shared by --tei build and --upgrade repair).
#
# Digest contract (v2, non-circular):
# - a completed SQLite file cannot contain its own byte digest, so `meta`
#   stores `content_sha256`: a deterministic digest over the dict rows
#   (`headword \t pos \t senses_json \n`, id order). Anyone holding the pack
#   can recompute and verify it.
# - the FILE digest (bytes on disk) is printed and written to a `.sha256`
#   sidecar next to the pack. The app's installer contract
#   (`DictionaryRuntime.PACK_SHA256`) must copy the sidecar value: it is the
#   digest of the shipped asset bytes, which the installer verifies at
#   first-use copy time.
# ---------------------------------------------------------------------------
def finalize(out_path, meta, n_rows, n_skipped):
    conn = sqlite3.connect(out_path)
    conn.execute("VACUUM")
    conn.close()
    file_sha = file_digest(out_path)
    sidecar = f"{out_path}.sha256"
    with open(sidecar, "w", encoding="utf-8") as fh:
        fh.write(file_sha + "\n")
    print(f"rows={n_rows} skipped={n_skipped}")
    for k, v in meta.items():
        print(f"{k}: {v}")
    print(f"file_sha256={file_sha} (sidecar: {sidecar})")
    print(
        "-> copy file_sha256 into DictionaryRuntime.PACK_SHA256 "
        "(mobile/app/src/main/kotlin/dev/folio/mobile/reader/DictionaryRuntime.kt)"
    )


def content_digest_out(out_path):
    """meta.content_sha256: sha256 over dict rows in id order."""
    conn = sqlite3.connect(out_path)
    try:
        rows = conn.execute(
            "SELECT headword, pos, senses_json FROM dict ORDER BY id"
        ).fetchall()
    finally:
        conn.close()
    buf = ""
    for headword, pos, senses_json in rows:
        buf += f"{headword}\t{pos or ''}\t{senses_json}\n"
    return hashlib.sha256(buf.encode("utf-8")).hexdigest()


def file_digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def upgrade(out_path):
    """Rewrite meta on an already-built pack (TEI not needed):
    - drop the v1 legacy `sha256` key (a circular, unreachable value);
    - add `content_sha256` recomputed over the actual rows;
    - keep every other key as recorded at build time;
    - VACUUM + sidecar + print the final file digest (installer target).
    """
    conn = sqlite3.connect(out_path)
    conn.execute("DELETE FROM meta WHERE key = 'sha256'")
    conn.commit()
    conn.close()
    existing = {}
    conn = sqlite3.connect(out_path)
    for k, v in conn.execute("SELECT key, value FROM meta").fetchall():
        existing[k] = v
    conn.close()
    if not existing:
        print("error: pack has no meta table content", file=sys.stderr)
        return 1
    meta = dict(existing)
    meta["content_sha256"] = content_digest_out(out_path)
    conn = sqlite3.connect(out_path)
    conn.execute("INSERT OR REPLACE INTO meta(key, value) VALUES ('content_sha256', ?)",
                 (meta["content_sha256"],))
    conn.commit()
    conn.close()
    finalize(out_path, meta, n_rows=int(existing.get("stored_rows", 0)), n_skipped=0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
