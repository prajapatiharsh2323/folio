import type { Annotation, DocMeta, ReadingPrefs } from "@folio/core";

export interface ReaderEngine {
  open(
    doc: DocMeta,
    el: HTMLElement,
    o: { location?: string; prefs: ReadingPrefs; epubBytes?: Uint8Array; url?: string }
  ): Promise<void>;
  goTo(location: string): Promise<void>;
  goToPercent?(percent: number): Promise<void>;
  next(): Promise<void>;
  prev(): Promise<void>;
  getToc(): Promise<{ title: string; location: string; depth: number; percent?: number }[]>;
  applyPrefs(p: ReadingPrefs): void;
  onRelocate(
    cb: (r: { location: string; percent: number; chapterId?: string; chapterTitle?: string }) => void
  ): void;
  onSelection(
    cb: (s: {
      quote: string;
      prefix: string;
      suffix: string;
      location: string;
      rect: DOMRect;
    }) => void
  ): void;
  addHighlight(a: Annotation): void;
  removeHighlight(id: string): void;
  search(q: string): AsyncIterable<{ location: string; excerpt: string }>;
  destroy(): void;
}
