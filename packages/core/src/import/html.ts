import type { ImportResult } from "./epub.js";
import { sha256Hex } from "../util/hash.js";
import { processClip, type ClipPayload } from "../clip/build-epub.js";

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
  const title = titleMatch && titleMatch[1] ? titleMatch[1].trim() : filename.replace(/\.html?$/i, "");

  // Extract author if available
  const authorMatch = rawHtml.match(/<meta[^>]*name=["']author["'][^>]*content=["']([^"']+)["']/i);
  const author = authorMatch && authorMatch[1] ? authorMatch[1].trim() : undefined;

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
    contentHtml: rawHtml,
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
          href: "clean/index.html"
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
