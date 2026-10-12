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
 * Strips XML/HTML tags and decodes common entities to obtain clean text.
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
    .replace(/\s+/g, " ")
    .trim();
}

/**
 * Imports an EPUB archive conforming to Section 6 of spec.md.
 */
export async function importEpub(bytes: Uint8Array, originalName = "book.epub"): Promise<ImportResult> {
  const unzipped = fflate.unzipSync(bytes);

  // Check DRM (Section 6 & 10)
  // If META-INF/encryption.xml lists anything other than font obfuscation, or META-INF/rights.xml exists:
  if (unzipped["META-INF/rights.xml"]) {
    throw new DrmProtectedError();
  }
  if (unzipped["META-INF/encryption.xml"]) {
    const encText = fflate.strFromU8(unzipped["META-INF/encryption.xml"]);
    // Allowed font obfuscation URIs
    const fontObfuscation1 = "http://www.idpf.org/2008/embedding";
    const fontObfuscation2 = "http://ns.adobe.com/pdf/enc/digital/book/2008/font";
    const hasOtherEncryption = encText.includes("CipherData") &&
      !encText.includes(fontObfuscation1) &&
      !encText.includes(fontObfuscation2);

    if (hasOtherEncryption) {
      throw new DrmProtectedError();
    }
  }

  // Find and read OPF
  const opfPath = findOpfPath(unzipped);
  const opfBytes = unzipped[opfPath];
  if (!opfBytes) {
    throw new Error(`Invalid EPUB: OPF package file not found at ${opfPath}`);
  }
  const opfText = fflate.strFromU8(opfBytes);

  // Extract title
  const titleMatch = opfText.match(/<dc:title[^>]*>([^<]+)<\/dc:title>/i);
  const title = titleMatch && titleMatch[1] ? titleMatch[1].trim() : "Untitled Book";

  // Extract author / creator
  const authorMatch = opfText.match(/<dc:creator[^>]*>([^<]+)<\/dc:creator>/i);
  const author = authorMatch && authorMatch[1] ? authorMatch[1].trim() : undefined;

  // Extract language
  const langMatch = opfText.match(/<dc:language[^>]*>([^<]+)<\/dc:language>/i);
  const language = langMatch && langMatch[1] ? langMatch[1].trim() : "en";

  // Base directory of the OPF file
  const opfDir = opfPath.includes("/") ? opfPath.substring(0, opfPath.lastIndexOf("/") + 1) : "";

  // Parse manifest items: id -> href
  const manifestMap = new Map<string, string>();
  const itemRegex = /<item\s+[^>]*id\s*=\s*["']([^"']+)["'][^>]*href\s*=\s*["']([^"']+)["'][^>]*\/?>/gi;
  let itemMatch: RegExpExecArray | null;
  while ((itemMatch = itemRegex.exec(opfText)) !== null) {
    const id = itemMatch[1]!;
    const href = itemMatch[2]!;
    manifestMap.set(id, href);
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

    const fullHref = `${opfDir}${href}`;
    const chapterBytes = unzipped[fullHref];
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
