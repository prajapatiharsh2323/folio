import * as pdfjsLib from "pdfjs-dist/legacy/build/pdf.mjs";
import type { Chapter, SourceFormat } from "../types.js";
import type { ImportResult } from "./epub.js";
import { sha256Hex } from "../util/hash.js";

/**
 * Section 6 PDF importer:
 * - Open with pdfjs-dist
 * - Read title and author from metadata
 * - Extract text per page
 * - Outline as chapters, otherwise one chapter
 * - Average under 50 characters a page means no text layer: set searchable: false
 * - Password-protected files show "This PDF is locked"
 */
export async function importPdf(bytes: Uint8Array, originalName = "document.pdf"): Promise<ImportResult> {
  const loadingTask = pdfjsLib.getDocument({
    data: bytes.slice(),
    useSystemFonts: true
  });

  let pdfDocument: pdfjsLib.PDFDocumentProxy;
  try {
    pdfDocument = await loadingTask.promise;
  } catch (err) {
    const error = err as Error;
    if (error.name === "PasswordException") {
      throw new Error("This PDF is locked");
    }
    throw error;
  }

  // Metadata
  let title = originalName.replace(/\.pdf$/i, "");
  let author: string | undefined;

  try {
    const metadataObj = await pdfDocument.getMetadata();
    const info = metadataObj?.info as Record<string, string> | undefined;
    if (info?.Title && info.Title.trim()) {
      title = info.Title.trim();
    }
    if (info?.Author && info.Author.trim()) {
      author = info.Author.trim();
    }
  } catch {
    // Fallback to filename
  }

  const numPages = pdfDocument.numPages;
  const chapters: Chapter[] = [];
  const searchTextList: { chapterId: string; title: string; text: string }[] = [];
  let totalCharacters = 0;
  const pageTexts: string[] = [];

  for (let pageNum = 1; pageNum <= numPages; pageNum++) {
    const page = await pdfDocument.getPage(pageNum);
    const textContent = await page.getTextContent();
    const pageString = textContent.items
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      .map((item: any) => item.str || "")
      .join(" ");

    pageTexts.push(pageString);
    totalCharacters += pageString.trim().length;

    const chapterId = `page-${pageNum}`;
    const chTitle = `Page ${pageNum}`;
    chapters.push({
      id: chapterId,
      title: chTitle,
      order: pageNum,
      href: `#page=${pageNum}`
    });

    searchTextList.push({
      chapterId,
      title: chTitle,
      text: pageString
    });
  }

  // Average under 50 characters a page means no text layer
  const avgChars = numPages > 0 ? totalCharacters / numPages : 0;
  const searchable = avgChars >= 50;

  const contentHash = await sha256Hex(bytes);

  return {
    meta: {
      schema: 1,
      title,
      author,
      kind: "book",
      language: "en",
      sourceFormat: "pdf" as SourceFormat,
      capturedAt: new Date().toISOString(),
      contentHash,
      searchable,
      tags: [],
      collectionIds: [],
      chapters
    },
    original: {
      name: originalName,
      bytes
    },
    searchText: searchTextList
  };
}
