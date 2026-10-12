import ePub, { Book, Rendition } from "epubjs";
import type { Annotation, DocMeta, ReadingPrefs } from "@folio/core";
import type { ReaderEngine } from "./reader-engine.js";
export type { ReaderEngine };

export class EpubEngine implements ReaderEngine {
  private book: Book | null = null;
  private rendition: Rendition | null = null;
  private relocateListeners: ((r: { location: string; percent: number; chapterId?: string }) => void)[] = [];
  private selectionListeners: ((s: {
    quote: string;
    prefix: string;
    suffix: string;
    location: string;
    rect: DOMRect;
  }) => void)[] = [];
  private highlightMap = new Map<string, string>(); // annotationId -> cfiRange

  async open(
    _doc: DocMeta,
    el: HTMLElement,
    o: { location?: string; prefs: ReadingPrefs; epubBytes?: Uint8Array; url?: string },
  ): Promise<void> {
    this.destroy();

    const bookSource = o.epubBytes ? o.epubBytes.buffer : (o.url || "");
    const book = ePub(bookSource as unknown as string);
    this.book = book;

    const rendition = book.renderTo(el, {
      width: "100%",
      height: "100%",
      flow: o.prefs.flow === "paginated" ? "paginated" : "scrolled-doc",
      spread: "none",
      allowScriptedContent: false // Critical rule: never run scripts from book or article content
    });
    this.rendition = rendition;

    this.applyPrefs(o.prefs);

    rendition.on("relocated", (location: { start?: { cfi?: string; percentage?: number } }) => {
      const cfi = location.start?.cfi || "";
      const percent = location.start?.percentage ? Math.round(location.start.percentage * 100) : 0;
      for (const listener of this.relocateListeners) {
        listener({ location: cfi, percent });
      }
    });

    rendition.on("selected", (cfiRange: string, contents: { window?: Window }) => {
      let quote = "";
      let prefix = "";
      let suffix = "";
      let rect = new DOMRect(0, 0, 0, 0);

      try {
        const win = contents?.window || window;
        const sel = win.getSelection();
        if (sel && sel.rangeCount > 0) {
          const range = sel.getRangeAt(0);
          quote = range.toString();
          rect = range.getBoundingClientRect();

          // Context extraction (32 chars each side)
          const nodeText = range.startContainer.textContent || "";
          const startOffset = range.startOffset;
          const endOffset = range.endOffset;
          prefix = nodeText.substring(Math.max(0, startOffset - 32), startOffset);
          suffix = nodeText.substring(endOffset, Math.min(nodeText.length, endOffset + 32));
        }
      } catch {
        // Fallback context extraction
      }

      for (const listener of this.selectionListeners) {
        listener({ quote, prefix, suffix, location: cfiRange, rect });
      }
    });

    await rendition.display(o.location || undefined);
  }

  async goTo(location: string): Promise<void> {
    if (this.rendition) {
      await this.rendition.display(location);
    }
  }

  async next(): Promise<void> {
    if (this.rendition) {
      await this.rendition.next();
    }
  }

  async prev(): Promise<void> {
    if (this.rendition) {
      await this.rendition.prev();
    }
  }

  async getToc(): Promise<{ title: string; location: string; depth: number }[]> {
    if (!this.book) return [];
    const navigation = await this.book.loaded.navigation;
    const items: { title: string; location: string; depth: number }[] = [];

    const traverse = (tocList: { label: string; href: string; subitems?: unknown[] }[], depth: number) => {
      for (const item of tocList) {
        items.push({
          title: item.label.trim(),
          location: item.href,
          depth
        });
        if (item.subitems && Array.isArray(item.subitems)) {
          traverse(item.subitems as { label: string; href: string; subitems?: unknown[] }[], depth + 1);
        }
      }
    };

    if (navigation?.toc) {
      traverse(navigation.toc, 0);
    }
    return items;
  }

  applyPrefs(p: ReadingPrefs): void {
    if (!this.rendition) return;

    this.rendition.themes.fontSize(`${p.fontSize}px`);

    const fontMap: Record<string, string> = {
      serif: '"Literata", "Noto Serif Devanagari", serif',
      sans: 'system-ui, -apple-system, "Noto Sans Devanagari", sans-serif'
    };
    this.rendition.themes.font(fontMap[p.fontFamily] || "serif");

    const themeStyles: Record<string, { bg: string; text: string }> = {
      light: { bg: "#FAFAF7", text: "#1C1B19" },
      warm: { bg: "#F4ECD8", text: "#3B3226" },
      dark: { bg: "#121214", text: "#E6E3DC" }
    };
    const active = themeStyles[p.theme] || themeStyles["light"]!;

    this.rendition.themes.default({
      body: {
        background: `${active.bg} !important`,
        color: `${active.text} !important`,
        "line-height": `${p.lineHeight} !important`,
        padding: `0 ${p.margin}px !important`,
        "text-align": p.justify ? "justify !important" : "left !important"
      }
    });
  }

  onRelocate(cb: (r: { location: string; percent: number; chapterId?: string }) => void): void {
    this.relocateListeners.push(cb);
  }

  onSelection(
    cb: (s: {
      quote: string;
      prefix: string;
      suffix: string;
      location: string;
      rect: DOMRect;
    }) => void,
  ): void {
    this.selectionListeners.push(cb);
  }

  addHighlight(a: Annotation): void {
    if (!this.rendition || !a.location) return;

    const colors: Record<string, string> = {
      yellow: "#FFE58A",
      green: "#B7E4B0",
      blue: "#A9D1F5",
      pink: "#F5B7CC"
    };
    const fill = colors[a.color || "yellow"] || "#FFE58A";

    this.rendition.annotations.highlight(
      a.location,
      {},
      () => {},
      `hl-${a.id}`,
      { fill, "fill-opacity": "0.45" }
    );
    this.highlightMap.set(a.id, a.location);
  }

  removeHighlight(id: string): void {
    if (!this.rendition) return;
    const cfiRange = this.highlightMap.get(id);
    if (cfiRange) {
      this.rendition.annotations.remove(cfiRange, "highlight");
      this.highlightMap.delete(id);
    }
  }

  async *search(q: string): AsyncIterable<{ location: string; excerpt: string }> {
    if (!this.book) return;
    // Iterate through spine items searching text
    const spine = (this.book as unknown as { spine?: { spineItems?: { load: (b: unknown) => Promise<Document>; unload: () => void; find: (query: string) => { cfi: string; excerpt: string }[] }[] } }).spine;
    if (!spine || !spine.spineItems) return;

    for (const item of spine.spineItems) {
      await item.load(this.book.load.bind(this.book));
      const results = item.find(q);
      item.unload();
      for (const res of results) {
        yield { location: res.cfi, excerpt: res.excerpt };
      }
    }
  }

  destroy(): void {
    if (this.rendition) {
      try {
        this.rendition.destroy();
      } catch {
        // Safe destroy
      }
      this.rendition = null;
    }
    if (this.book) {
      try {
        this.book.destroy();
      } catch {
        // Safe destroy
      }
      this.book = null;
    }
    this.relocateListeners = [];
    this.selectionListeners = [];
    this.highlightMap.clear();
  }
}
