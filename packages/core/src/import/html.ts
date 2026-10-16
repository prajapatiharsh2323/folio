import type { ImportResult } from "./epub.js";
import { sha256Hex } from "../util/hash.js";
import { processClip, type ClipPayload } from "../clip/build-epub.js";
import { isDevanagariDominant } from "./txt.js";

import { Readability } from "@mozilla/readability";

/**
 * Imports an HTML or SingleFile document conforming to Section 6.
 */
export async function importHtml(
  bytes: Uint8Array,
  filename = "article.html",
  windowObj?: Window
): Promise<ImportResult> {
  const decoder = new TextDecoder("utf-8");
  const rawHtml = decoder.decode(bytes);

  // Extract title
  const titleMatch = rawHtml.match(/<title[^>]*>([^<]+)<\/title>/i);
  let title = titleMatch && titleMatch[1] ? titleMatch[1].trim() : "";

  // Extract author if available
  const authorMatch = rawHtml.match(/<meta[^>]*name=["']author["'][^>]*content=["']([^"']+)["']/i);
  let author = authorMatch && authorMatch[1] ? authorMatch[1].trim() : undefined;

  // Extract language: check <html lang="..."> or check if Devanagari is dominant
  const langMatch = rawHtml.match(/<html[^>]*lang=["']([^"']+)["']/i);
  let lang = langMatch && langMatch[1] ? langMatch[1].trim() : undefined;
  if (!lang && isDevanagariDominant(rawHtml)) {
    lang = "hi";
  }

  // Extract clean article content via Readability to strip nav, ads, footers
  let contentHtml = rawHtml;
  try {
    let doc: Document | null = null;
    if (windowObj && "DOMParser" in (windowObj as unknown as Record<string, unknown>)) {
      doc = new (windowObj as unknown as { DOMParser: typeof DOMParser }).DOMParser().parseFromString(rawHtml, "text/html");
    } else if (typeof window !== "undefined" && typeof DOMParser !== "undefined") {
      doc = new DOMParser().parseFromString(rawHtml, "text/html");
    } else {
      // eslint-disable-next-line @typescript-eslint/no-require-imports
      const { JSDOM } = require("jsdom");
      doc = new JSDOM(rawHtml).window.document;
    }

    if (doc) {
      const reader = new Readability(doc);
      const parsed = reader.parse();
      if (parsed && parsed.content) {
        contentHtml = parsed.content;
        if (!title && parsed.title) title = parsed.title;
        if (!author && parsed.byline) author = parsed.byline;
      }
    }
  } catch {
    // Fallback to rawHtml if DOM/Readability extraction fails
  }

  if (!title) {
    title = filename.replace(/\.html?$/i, "");
  }

  // SingleFile pages embed images as data URIs; extract them as files
  const images: { originalUrl: string; mime: string; base64: string }[] = [];
  const imgRegex = /src=["'](data:(image\/[^;]+);base64,([^"']+))["']/gi;
  let match: RegExpExecArray | null;
  let imgIndex = 0;

  while ((match = imgRegex.exec(rawHtml)) !== null) {
    imgIndex++;
    images.push({
      originalUrl: match[1]!,
      mime: match[2]!,
      base64: match[3]!
    });
    if (imgIndex >= 40) break; // Section 6: cap at 40 images
  }

  const payload: ClipPayload = {
    version: 1,
    url: filename,
    title,
    byline: author,
    lang,
    contentHtml,
    images
  };

  const processed = await processClip(payload, windowObj);
  const contentHash = await sha256Hex(bytes);

  return {
    meta: {
      schema: 1,
      title: processed.title,
      author: processed.author,
      kind: "article",
      language: processed.lang,
      sourceFormat: "html",
      capturedAt: new Date().toISOString(),
      contentHash,
      searchable: true,
      tags: [],
      collectionIds: [],
      chapters: [
        {
          id: "ch-1",
          title: processed.title,
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
      images: processed.images
    },
    reading: {
      name: "book.epub",
      bytes: processed.readingEpub
    },
    searchText: [
      {
        chapterId: "ch-1",
        title: processed.title,
        text: processed.cleanHtml.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim()
      }
    ]
  };
}
