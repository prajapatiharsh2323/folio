import * as fflate from "fflate";
import type { Chapter, DocMeta, SourceFormat } from "../types.js";
import { sha256Hex } from "../util/hash.js";

export interface ImportResult {
  meta: Omit<DocMeta, "id" | "slug" | "importedAt" | "files">;
  original: { name: string; bytes: Uint8Array };
  clean?: { indexHtml: string; images: { name: string; bytes: Uint8Array }[] };
  reading?: { name: "book.epub"; bytes: Uint8Array };
  searchText: { chapterId: string; title: string; text: string }[];
}

export class DrmProtectedError extends Error {
  constructor(message = "This EPUB is DRM-protected and cannot be imported.") {
    super(message);
    this.name = "DrmProtectedError";
  }
}

/**
 * Parses container.xml to locate the OPF package file.
 */
function findOpfPath(zip: Record<string, Uint8Array>): string {
  const containerBytes = zip["META-INF/container.xml"];
  if (!containerBytes) {
    throw new Error("Invalid EPUB: META-INF/container.xml is missing");
  }
  const text = fflate.strFromU8(containerBytes);
  const match = text.match(/full-path\s*=\s*["']([^"']+)["']/i);
  if (!match || !match[1]) {
    throw new Error("Invalid EPUB: could not locate package full-path in container.xml");
  }
  return match[1];
}

/**
 * Strips XML/HTML tags and decodes common and numeric entities to obtain clean text.
 */
function extractPlainText(xml: string): string {
  return xml
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#39;/g, "'")
    .replace(/&#(\d+);/g, (_, dec) => {
      try {
        return String.fromCodePoint(parseInt(dec, 10));
      } catch {
        return "";
      }
    })
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => {
      try {
        return String.fromCodePoint(parseInt(hex, 16));
      } catch {
        return "";
      }
    })
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Resolves a relative path against an OPF directory handling ".." and ".".
 */
function resolvePath(baseDir: string, relativePath: string): string {
  const combined = baseDir ? `${baseDir}${relativePath}` : relativePath;
  const parts = combined.split("/");
  const resolved: string[] = [];
  for (const p of parts) {
    if (p === "." || p === "") continue;
    if (p === "..") {
      resolved.pop();
    } else {
      resolved.push(p);
    }
  }
  return resolved.join("/");
}

/**
 * Imports an EPUB archive conforming to Section 6 of spec.md.
 */
export async function importEpub(bytes: Uint8Array, originalName = "book.epub"): Promise<ImportResult> {
  const unzipped = fflate.unzipSync(bytes);

  // Check DRM (Section 6 & 10)
  // If META-INF/rights.xml exists or encryption.xml lists anything other than font obfuscation:
  if (unzipped["META-INF/rights.xml"]) {
    throw new DrmProtectedError();
  }
  if (unzipped["META-INF/encryption.xml"]) {
    const encText = fflate.strFromU8(unzipped["META-INF/encryption.xml"]);
    // Allowed standard font obfuscation URIs (IDPF and Adobe font mangling)
    const allowedFontAlgos = [
      "http://www.idpf.org/2008/embedding",
      "http://ns.adobe.com/pdf/enc#RC"
    ];

    const algoMatches = Array.from(
      encText.matchAll(/<(?:\w+:)?EncryptionMethod\s+[^>]*Algorithm=["']([^"']+)["']/gi)
    );
    if (algoMatches.length > 0) {
      for (const m of algoMatches) {
        if (!allowedFontAlgos.includes(m[1]!)) {
          throw new DrmProtectedError();
        }
      }
    } else if (encText.includes("CipherData")) {
      throw new DrmProtectedError();
    }
  }

  // Find and read OPF
  const opfPath = findOpfPath(unzipped);
  let opfBytes = unzipped[opfPath];
  if (!opfBytes) {
    try {
      opfBytes = unzipped[decodeURIComponent(opfPath)];
    } catch {
      // ignore
    }
  }
  if (!opfBytes) {
    const lower = opfPath.toLowerCase();
    const foundKey = Object.keys(unzipped).find((k) => k.toLowerCase() === lower);
    if (foundKey) {
      opfBytes = unzipped[foundKey];
    }
  }
  if (!opfBytes) {
    throw new Error(`Invalid EPUB: OPF package file not found at ${opfPath}`);
  }
  const opfText = fflate.strFromU8(opfBytes);

  // Extract title (handles default xmlns without prefix, prefixes like dcterms:, multiline content)
  const titleMatch = opfText.match(/<(?:\w+:)?title\b[^>]*>([\s\S]*?)<\/(?:\w+:)?title>/i);
  const title = (titleMatch?.[1] ? extractPlainText(titleMatch[1]) : "").trim() || "Untitled Book";

  // Extract author / creator
  const authorMatch = opfText.match(/<(?:\w+:)?creator\b[^>]*>([\s\S]*?)<\/(?:\w+:)?creator>/i);
  const author = (authorMatch?.[1] ? extractPlainText(authorMatch[1]) : "").trim() || undefined;

  // Extract language
  const langMatch = opfText.match(/<(?:\w+:)?language\b[^>]*>([\s\S]*?)<\/(?:\w+:)?language>/i);
  const language = (langMatch?.[1] ? extractPlainText(langMatch[1]) : "").trim() || "en";

  // Base directory of the OPF file
  const opfDir = opfPath.includes("/") ? opfPath.substring(0, opfPath.lastIndexOf("/") + 1) : "";

  // Parse manifest items flexibly without assuming attribute order
  const manifestMap = new Map<string, string>();
  const itemTagRegex = /<item\b([^>]*)\/?>/gi;
  let itemMatch: RegExpExecArray | null;
  while ((itemMatch = itemTagRegex.exec(opfText)) !== null) {
    const attrs = itemMatch[1]!;
    const idMatch = attrs.match(/\bid\s*=\s*["']([^"']+)["']/i);
    const hrefMatch = attrs.match(/\bhref\s*=\s*["']([^"']+)["']/i);
    if (idMatch && hrefMatch) {
      const id = idMatch[1]!;
      const rawHref = hrefMatch[1]!;
      try {
        manifestMap.set(id, decodeURIComponent(rawHref));
      } catch {
        manifestMap.set(id, rawHref);
      }
    }
  }

  // Parse spine itemrefs to follow reading order
  const spineItems: string[] = [];
  const spineRegex = /<itemref\s+[^>]*idref\s*=\s*["']([^"']+)["'][^>]*\/?>/gi;
  let spineMatch: RegExpExecArray | null;
  while ((spineMatch = spineRegex.exec(opfText)) !== null) {
    spineItems.push(spineMatch[1]!);
  }

  const chapters: Chapter[] = [];
  const searchTextList: { chapterId: string; title: string; text: string }[] = [];
  let totalWordCount = 0;

  for (let i = 0; i < spineItems.length; i++) {
    const itemId = spineItems[i]!;
    const href = manifestMap.get(itemId);
    if (!href) continue;

    const fullHref = resolvePath(opfDir, href);
    let chapterBytes = unzipped[fullHref] || unzipped[`${opfDir}${href}`] || unzipped[href];
    if (!chapterBytes) {
      try {
        const decodedFull = decodeURIComponent(fullHref);
        const decodedHref = decodeURIComponent(href);
        chapterBytes = unzipped[decodedFull] || unzipped[`${opfDir}${decodedHref}`] || unzipped[decodedHref];
      } catch {
        // ignore
      }
    }
    if (!chapterBytes) {
      const lowerFull = fullHref.toLowerCase();
      const foundKey = Object.keys(unzipped).find((k) => k.toLowerCase() === lowerFull);
      if (foundKey) {
        chapterBytes = unzipped[foundKey];
      }
    }
    let chTitle = `Chapter ${i + 1}`;
    let plainText = "";

    if (chapterBytes) {
      const chHtml = fflate.strFromU8(chapterBytes);
      const titleTag = chHtml.match(/<title[^>]*>([^<]+)<\/title>/i);
      const hTag = chHtml.match(/<h[1-3][^>]*>([^<]+)<\/h[1-3]>/i);
      if (titleTag && titleTag[1]?.trim()) {
        chTitle = titleTag[1].trim();
      } else if (hTag && hTag[1]?.trim()) {
        chTitle = hTag[1].trim();
      }

      plainText = extractPlainText(chHtml);
      const words = plainText.split(/\s+/).filter(Boolean);
      totalWordCount += words.length;
    }

    const chapterId = `ch-${i + 1}`;
    chapters.push({
      id: chapterId,
      title: chTitle,
      order: i + 1,
      href: fullHref
    });

    searchTextList.push({
      chapterId,
      title: chTitle,
      text: plainText
    });
  }

  const contentHash = await sha256Hex(bytes);

  return {
    meta: {
      schema: 1,
      title,
      author,
      kind: "book",
      language,
      sourceFormat: "epub" as SourceFormat,
      capturedAt: new Date().toISOString(),
      contentHash,
      wordCount: totalWordCount,
      searchable: true,
      tags: [],
      collectionIds: [],
      chapters
    },
    original: {
      name: originalName,
      bytes
    },
    reading: {
      name: "book.epub",
      bytes
    },
    searchText: searchTextList
  };
}
