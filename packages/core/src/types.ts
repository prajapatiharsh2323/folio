export type DocKind = "article" | "book";
export type SourceFormat = "epub" | "pdf" | "html" | "txt" | "clip";

export interface Chapter {
  id: string;
  title: string;
  order: number;
  href: string; // href relative to the doc folder
}

export interface DocMeta {
  schema: 1;
  id: string; // crypto.randomUUID()
  slug: string;
  title: string;
  author?: string;
  kind: DocKind;
  language?: string; // BCP 47, e.g. 'en', 'hi'
  sourceFormat: SourceFormat;
  sourceUrl?: string; // clips and web-origin files
  sourceName?: string; // site or publisher name
  capturedAt: string; // ISO 8601 UTC
  importedAt: string;
  contentHash: string; // SHA-256 hex of the original bytes (clips: of clean/index.html)
  wordCount?: number;
  searchable: boolean; // false for scanned PDFs without a text layer
  tags: string[];
  collectionIds: string[];
  files: {
    original?: string;
    clean?: string;
    reading?: string;
    cover?: string;
  };
  chapters: Chapter[];
}

export interface Progress {
  docId: string;
  location: string;
  percent: number;
  updatedAt: string; // location: EPUB CFI, PDF page, or scroll offset
}

export interface Annotation {
  id: string;
  docId: string;
  chapterId?: string;
  quote: string;
  prefix: string;
  suffix: string; // 32 characters of context each side
  location?: string; // engine-specific hint; quote+context is authoritative
  kind: "highlight" | "note" | "bookmark";
  color?: "yellow" | "green" | "blue" | "pink";
  note?: string;
  createdAt: string;
  updatedAt: string;
}

export interface Collection {
  id: string;
  name: string;
  docIds: string[];
  createdAt: string;
}

export interface ManifestFile {
  path: string;
  size: number;
  sha256: string;
}

export interface ManifestDoc {
  id: string;
  slug: string;
  title: string;
  contentHash: string;
  updatedAt: string;
  files: ManifestFile[];
}

export interface Manifest {
  schema: 1;
  app: "folio";
  libraryId: string;
  updatedAt: string;
  docs: ManifestDoc[];
}

export interface ReadingPrefs {
  theme: "light" | "warm" | "dark" | "system";
  fontFamily: "serif" | "sans" | "mono" | "publisher";
  fontSize: number;          // 14 - 32 px
  lineHeight: number;        // 1.3 - 2.4
  margin: number;            // 12 - 64 px horizontal gutter
  justify: boolean;
  hyphenate?: boolean;
  paragraphSpacing?: number; // 0 - 1.5 em
  letterSpacing?: number;    // -0.02 - 0.08 em
  maxWidth?: number;         // 560 - 960 px
  brightness?: number;       // 40 - 100 %
  flow: "paginated" | "scrolled";
  spread?: "none" | "auto";  // Two-page spread on wide screens
  pdfZoom?: "fit-width" | "fit-page" | number;
}
