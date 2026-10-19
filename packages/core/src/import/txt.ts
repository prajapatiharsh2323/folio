import type { ImportResult } from "./epub.js";
import { sha256Hex } from "../util/hash.js";
import { processClip } from "../clip/build-epub.js";

/**
 * Checks if more than 30% of characters are in Devanagari script (U+0900 to U+097F).
 */
export function isDevanagariDominant(text: string): boolean {
  const lettersOnly = text.replace(/[^\p{L}]/gu, "");
  if (lettersOnly.length === 0) return false;

  let devanagariCount = 0;
  for (const char of lettersOnly) {
    const code = char.codePointAt(0);
    if (code && code >= 0x0900 && code <= 0x097f) {
      devanagariCount++;
    }
  }

  return devanagariCount / lettersOnly.length > 0.3;
}

/**
 * Imports a plain text file conforming to Section 6:
 * - Splits on blank lines into paragraphs
 * - Title from the file name
 * - Handles UTF-8 and UTF-8 with BOM
 * - If over 30% of letters are Devanagari, set language: 'hi'
 */
export async function importTxt(bytes: Uint8Array, filename = "document.txt"): Promise<ImportResult> {
  let content = new TextDecoder("utf-8").decode(bytes);

  // Handle UTF-8 with BOM
  if (content.charCodeAt(0) === 0xfeff) {
    content = content.substring(1);
  }

  const title = filename.replace(/\.txt$/i, "");
  const isHindi = isDevanagariDominant(content);
  const language = isHindi ? "hi" : "en";

  // Split on blank lines into paragraphs
  const paragraphs = content
    .split(/\r?\n\s*\r?\n/)
    .map((p) => p.trim())
    .filter(Boolean);

  const bodyHtml = paragraphs.map((p) => `<p>${p.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")}</p>`).join("\n");

  const processed = await processClip({
    version: 1,
    url: filename,
    title,
    lang: language,
    contentHtml: bodyHtml,
    images: []
  });

  const contentHash = await sha256Hex(bytes);

  return {
    meta: {
      schema: 1,
      title,
      kind: "article",
      language,
      sourceFormat: "txt",
      capturedAt: new Date().toISOString(),
      contentHash,
      searchable: true,
      tags: [],
      collectionIds: [],
      chapters: [
        {
          id: "ch-1",
          title,
          order: 1,
          href: "chapter_1.xhtml"
        }
      ]
    },
    original: {
      name: filename,
      bytes
    },
    clean: {
      indexHtml: processed.cleanIndexHtml,
      images: []
    },
    reading: {
      name: "book.epub",
      bytes: processed.readingEpub
    },
    searchText: [
      {
        chapterId: "ch-1",
        title,
        text: content
      }
    ]
  };
}
