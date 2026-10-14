import ePub, { Book, Rendition } from "epubjs";
import { sanitizeSectionDoc, sanitizeSerializedHtml, type Annotation, type DocMeta, type ReadingPrefs } from "@folio/core";
import type { ReaderEngine } from "./reader-engine.js";
export type { ReaderEngine };
export { sanitizeSectionDoc, sanitizeSerializedHtml };

export class EpubEngine implements ReaderEngine {
  private book: Book | null = null;
  private rendition: Rendition | null = null;
  private isDestroyed = false;
  private registeredBooks = new WeakSet<Book>();
  private relocateListeners: ((r: {
    location: string;
    percent: number;
    chapterId?: string;
    chapterTitle?: string;
  }) => void)[] = [];
  private selectionListeners: ((s: {
    quote: string;
    prefix: string;
    suffix: string;
    location: string;
    rect: DOMRect;
  }) => void)[] = [];
  private highlightMap = new Map<string, string>(); // annotationId -> cfiRange
  private cachedBytes?: Uint8Array;
  private cachedDocId?: string;
  private containerEl: HTMLElement | null = null;
  private currentPrefs: ReadingPrefs | null = null;
  private lastRelocatedCfi = "";
  private chapterMap = new Map<string, string>(); // href -> chapterTitle
  private resizeObserver: ResizeObserver | null = null;
  private resizeTimer: ReturnType<typeof setTimeout> | null = null;

  private registerSecurityHooks(book: Book): void {
    if (this.registeredBooks.has(book)) return;
    this.registeredBooks.add(book);

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const spineHooks = (book.spine as any)?.hooks;
    if (spineHooks) {
      if (spineHooks.content && typeof spineHooks.content.register === "function") {
        spineHooks.content.register((doc: Document) => {
          sanitizeSectionDoc(doc);
        });
      }
      if (spineHooks.serialize && typeof spineHooks.serialize.register === "function") {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        spineHooks.serialize.register((output: string, section: any) => {
          sanitizeSerializedHtml(output, section);
        });
      }
    }
  }

  private observeContainerResize(el: HTMLElement): void {
    this.resizeObserver?.disconnect();
    if (typeof ResizeObserver === "undefined") return;

    this.resizeObserver = new ResizeObserver((entries) => {
      if (this.isDestroyed || !this.rendition) return;
      const entry = entries[0];
      if (!entry) return;
      const { width, height } = entry.contentRect;
      if (width <= 0 || height <= 0) return;

      if (this.resizeTimer) clearTimeout(this.resizeTimer);
      this.resizeTimer = setTimeout(() => {
        if (!this.isDestroyed && this.rendition) {
          try {
            this.rendition.resize(Math.floor(width), Math.floor(height));
          } catch {
            // ignore transient resize errors
          }
        }
      }, 100);
    });

    this.resizeObserver.observe(el);
  }

  async open(
    _doc: DocMeta,
    el: HTMLElement,
    o: { location?: string; prefs: ReadingPrefs; epubBytes?: Uint8Array; url?: string },
  ): Promise<void> {
    this.destroy();
    this.isDestroyed = false;
    this.containerEl = el;
    this.currentPrefs = o.prefs;

    if (o.epubBytes && o.epubBytes.byteLength > 0) {
      this.cachedBytes = o.epubBytes;
      this.cachedDocId = _doc.id;
    }

    // Check container dimensions
    if (el.clientHeight === 0) {
      console.warn("EpubEngine: container element clientHeight is 0 at open() call");
    }

    // Clean any prior children in the host element
    el.innerHTML = "";

    let bookSource: Blob | string;
    const canUseCache = Boolean(this.cachedBytes && this.cachedDocId === _doc.id);
    const bytesToUse = o.epubBytes && o.epubBytes.byteLength > 0 ? o.epubBytes : (canUseCache ? this.cachedBytes : undefined);
    if (bytesToUse && bytesToUse.byteLength > 0) {
      bookSource = new Blob([bytesToUse as unknown as BlobPart], { type: "application/epub+zip" });
    } else if (o.url) {
      bookSource = o.url;
    } else {
      throw new Error("No EPUB source available to open.");
    }

    const book = ePub(bookSource as unknown as string);
    this.book = book;
    this.registerSecurityHooks(book);

    const isWide = el.clientWidth >= 1024;
    const rendition = book.renderTo(el, {
      width: "100%",
      height: "100%",
      flow: o.prefs.flow === "paginated" ? "paginated" : "scrolled-doc",
      spread: o.prefs.flow === "paginated" && o.prefs.spread === "auto" && isWide ? "auto" : "none",
      allowScriptedContent: false // Critical rule: never run scripts from book or article content
    });
    this.rendition = rendition;
    this.observeContainerResize(el);

    this.applyPrefs(o.prefs);
    this.setupHooksAndListeners(rendition, el);

    // Ensure package & spine are fully parsed before display
    try {
      await book.ready;
      // Preload navigation for chapter titles
      this.preloadNavigation();
    } catch (err) {
      if (this.isDestroyed) return;
      console.error("EpubEngine: book.ready failed:", err);
      throw err;
    }

    if (this.isDestroyed) return;

    try {
      if (o.location && typeof o.location === "string" && o.location.trim() !== "" && !o.location.includes("clean/index.html")) {
        await rendition.display(o.location);
      } else {
        await rendition.display();
      }
    } catch (err) {
      console.warn("EpubEngine: initial display attempt failed:", err);
      if (!this.isDestroyed) {
        try {
          await rendition.display();
        } catch (fallbackErr) {
          console.error("EpubEngine: fallback display failed:", fallbackErr);
          throw fallbackErr;
        }
      }
    }

    // Verify rendition loaded a location
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const currLoc = (rendition as any).currentLocation?.();
    if (!currLoc?.start?.cfi) {
      console.warn("EpubEngine: rendition.currentLocation() has no CFI after display().");
    } else {
      this.lastRelocatedCfi = currLoc.start.cfi;
    }

    // Generate book locations asynchronously AFTER first display to prevent racing section loading
    book.ready
      .then(() => {
        if (this.isDestroyed) return;
        return book.locations.generate(1024);
      })
      .then(() => {
        if (this.isDestroyed || !this.rendition) return;
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const curr = (this.rendition as any).currentLocation?.();
        if (curr?.start?.cfi && this.book?.locations) {
          const cfi = curr.start.cfi;
          this.lastRelocatedCfi = cfi;
          const p = this.book.locations.percentageFromCfi(cfi);
          if (p !== undefined && !isNaN(p)) {
            const percent = Math.round(p * 1000) / 10;
            const chapterTitle = this.lookupChapterTitle(curr?.start?.href || "");
            for (const listener of this.relocateListeners) {
              listener({ location: cfi, percent, chapterId: curr?.start?.href, chapterTitle });
            }
          }
        }
      })
      .catch((err) => {
        console.warn("EpubEngine locations generation:", err);
      });
  }

  private preloadNavigation(): void {
    if (!this.book) return;
    this.book.loaded.navigation
      .then((nav) => {
        if (!nav || !nav.toc) return;
        const traverse = (tocList: { label?: string; href?: string; subitems?: unknown[] }[]) => {
          for (const item of tocList) {
            const title = (item.label || "").trim();
            if (item.href && title) {
              this.chapterMap.set(item.href, title);
              const clean = item.href.split("#")[0];
              if (clean) this.chapterMap.set(clean, title);
            }
            if (item.subitems && Array.isArray(item.subitems)) {
              traverse(item.subitems as { label?: string; href?: string; subitems?: unknown[] }[]);
            }
          }
        };
        traverse(nav.toc);
      })
      .catch(() => {});
  }

  private lookupChapterTitle(href: string): string | undefined {
    if (!href) return undefined;
    if (this.chapterMap.has(href)) return this.chapterMap.get(href);
    const clean = href.split("#")[0] || "";
    if (clean && this.chapterMap.has(clean)) return this.chapterMap.get(clean);
    for (const [k, v] of this.chapterMap.entries()) {
      if (clean && (k.endsWith(clean) || clean.endsWith(k))) return v;
    }
    return undefined;
  }

  private setupHooksAndListeners(rendition: Rendition, el: HTMLElement): void {
    // Register iframe content hook for tap zones, clicks, and gestures
    rendition.hooks.content.register((contents: { document?: Document }) => {
      const doc = contents.document;
      if (!doc) return;
      sanitizeSectionDoc(doc);

      let touchStartX = 0;
      let touchStartY = 0;

      doc.addEventListener(
        "touchstart",
        (e: TouchEvent) => {
          if (e.touches[0]) {
            touchStartX = e.touches[0].clientX;
            touchStartY = e.touches[0].clientY;
          }
          el.dispatchEvent(new CustomEvent("folio:touch-start", { bubbles: true }));
        },
        { passive: true }
      );

      doc.addEventListener(
        "touchend",
        (e: TouchEvent) => {
          el.dispatchEvent(new CustomEvent("folio:touch-end", { bubbles: true }));
          if (e.changedTouches[0]) {
            const diffX = e.changedTouches[0].clientX - touchStartX;
            const diffY = e.changedTouches[0].clientY - touchStartY;
            if (Math.abs(diffX) > 50 && Math.abs(diffX) > Math.abs(diffY)) {
              if (diffX < 0) {
                this.next();
              } else {
                this.prev();
              }
            }
          }
        },
        { passive: true }
      );

      doc.addEventListener("click", (e: MouseEvent) => {
        // Tapping links inside the book must navigate the link, not turn the page
        if ((e.target as HTMLElement)?.closest("a")) {
          return;
        }
        const sel = doc.getSelection();
        if (sel && sel.toString().trim().length > 0) {
          return;
        }
        // Dismiss any existing floating toolbar on page click
        el.dispatchEvent(new CustomEvent("folio:clear-selection", { bubbles: true }));

        const width = doc.defaultView?.innerWidth || doc.documentElement.clientWidth || window.innerWidth;
        const x = e.clientX;
        if (x < width / 3) {
          this.prev();
        } else if (x > (2 * width) / 3) {
          this.next();
        } else {
          el.dispatchEvent(new CustomEvent("folio:middle-tap", { bubbles: true }));
        }
      });
    });

    rendition.on("relocated", (location: { start?: { cfi?: string; percentage?: number; href?: string } }) => {
      if (this.isDestroyed) return;
      const cfi = location.start?.cfi || "";
      if (cfi) this.lastRelocatedCfi = cfi;

      let percent = 0;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const locations = (this.book as any)?.locations;
      if (locations && typeof locations.percentageFromCfi === "function" && locations.length() > 0) {
        const p = locations.percentageFromCfi(cfi);
        if (p !== undefined && !isNaN(p)) {
          percent = Math.round(p * 1000) / 10;
        }
      } else if (location.start?.percentage !== undefined && location.start.percentage > 0) {
        percent = Math.round(location.start.percentage * 1000) / 10;
      } else if (this.book?.spine) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const spine = this.book.spine as any;
        const spineIndex = spine.get ? spine.get(cfi)?.index ?? 0 : 0;
        const total = spine.length || 1;
        percent = Math.round((spineIndex / total) * 1000) / 10;
      }

      let chapterId = location.start?.href;
      let chapterTitle: string | undefined;
      try {
        if (this.book?.spine) {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const spine = this.book.spine as any;
          const spineItem = spine.get ? spine.get(cfi) : null;
          if (spineItem?.href) {
            chapterId = spineItem.href;
            chapterTitle = this.lookupChapterTitle(spineItem.href);
          }
        }
      } catch {}

      for (const listener of this.relocateListeners) {
        listener({ location: cfi, percent, chapterId, chapterTitle });
      }
    });

    rendition.on("selected", (cfiRange: string, contents: { window?: Window; document?: Document }) => {
      if (this.isDestroyed) return;
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

          // Context extraction (32 chars each side)
          const nodeText = range.startContainer.textContent || "";
          const startOffset = range.startOffset;
          const endOffset = range.endOffset;
          prefix = nodeText.substring(Math.max(0, startOffset - 32), startOffset);
          suffix = nodeText.substring(endOffset, Math.min(nodeText.length, endOffset + 32));

          // Selection toolbar absolute positioning including iframe offset (§2e)
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const frameEl = ((contents as any)?.document?.defaultView?.frameElement || (contents as any)?.window?.frameElement) as HTMLElement | null;
          const frameRect = frameEl
            ? frameEl.getBoundingClientRect()
            : (this.containerEl?.querySelector("iframe")?.getBoundingClientRect() ?? new DOMRect(0, 0, 0, 0));
          const r = range.getBoundingClientRect();
          rect = new DOMRect(
            frameRect.left + r.left,
            frameRect.top + r.top,
            r.width,
            r.height
          );
        }
      } catch (err) {
        console.warn("EpubEngine selection extraction failed:", err);
      }

      for (const listener of this.selectionListeners) {
        listener({ quote, prefix, suffix, location: cfiRange, rect });
      }
    });
  }

  async reopenWithFlow(flow: "paginated" | "scrolled", targetLocation?: string): Promise<void> {
    if (!this.book || !this.containerEl || this.isDestroyed) return;

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const locToRestore = targetLocation || this.lastRelocatedCfi || (this.rendition as any)?.currentLocation?.()?.start?.cfi;

    if (this.rendition) {
      try {
        this.rendition.destroy();
      } catch {
        // ignore
      }
      this.rendition = null;
    }

    this.containerEl.innerHTML = "";
    this.registerSecurityHooks(this.book);

    const isWide = (this.containerEl.clientWidth || 0) >= 1024;
    const rendition = this.book.renderTo(this.containerEl, {
      width: "100%",
      height: "100%",
      flow: flow === "paginated" ? "paginated" : "scrolled-doc",
      spread: flow === "paginated" && this.currentPrefs?.spread === "auto" && isWide ? "auto" : "none",
      allowScriptedContent: false
    });
    this.rendition = rendition;
    this.observeContainerResize(this.containerEl);

    if (this.currentPrefs) {
      this.currentPrefs = { ...this.currentPrefs, flow };
      this.applyPrefs(this.currentPrefs);
    }

    this.setupHooksAndListeners(rendition, this.containerEl);

    try {
      if (locToRestore && typeof locToRestore === "string" && locToRestore.trim() !== "" && !locToRestore.includes("clean/index.html")) {
        await rendition.display(locToRestore);
      } else {
        await rendition.display();
      }
    } catch (err) {
      console.warn("EpubEngine reopenWithFlow display failed:", err);
    }

    // Reapply existing highlights to new rendition
    for (const [id, cfiRange] of this.highlightMap.entries()) {
      try {
        rendition.annotations.highlight(
          cfiRange,
          {},
          () => {},
          `hl-${id}`,
          { fill: "#FFE58A", "fill-opacity": "0.45" }
        );
      } catch {}
    }
  }

  async goTo(location: string): Promise<void> {
    if (this.rendition && !this.isDestroyed) {
      await this.rendition.display(location);
    }
  }

  async next(): Promise<void> {
    if (this.rendition && !this.isDestroyed) {
      await this.rendition.next();
    }
  }

  async prev(): Promise<void> {
    if (this.rendition && !this.isDestroyed) {
      await this.rendition.prev();
    }
  }

  async getToc(): Promise<{ title: string; location: string; depth: number; percent?: number }[]> {
    if (!this.book || this.isDestroyed) return [];
    try {
      const navigation = await this.book.loaded.navigation;
      if (this.isDestroyed) return [];
      const items: { title: string; location: string; depth: number; percent?: number }[] = [];

      const traverse = (tocList: { label: string; href: string; subitems?: unknown[] }[], depth: number) => {
        for (const item of tocList) {
          const title = (item.label || "").trim();
          let percent: number | undefined;
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const locations = (this.book as any)?.locations;
          if (locations && locations.length() > 0 && item.href) {
            try {
              const p = locations.percentageFromCfi(item.href);
              if (p !== undefined && !isNaN(p)) {
                percent = Math.round(p * 1000) / 10;
              }
            } catch {}
          }

          items.push({
            title,
            location: item.href,
            depth,
            percent
          });
          if (item.href && title) {
            this.chapterMap.set(item.href, title);
            const clean = item.href.split("#")[0];
            if (clean) this.chapterMap.set(clean, title);
          }
          if (item.subitems && Array.isArray(item.subitems)) {
            traverse(item.subitems as { label: string; href: string; subitems?: unknown[] }[], depth + 1);
          }
        }
      };

      if (navigation?.toc) {
        traverse(navigation.toc, 0);
      }
      return items;
    } catch {
      return [];
    }
  }

  applyPrefs(p: ReadingPrefs): void {
    const previousFlow = this.currentPrefs?.flow;
    const previousSpread = this.currentPrefs?.spread;
    this.currentPrefs = p;

    if (
      this.rendition &&
      !this.isDestroyed &&
      ((previousFlow && previousFlow !== p.flow) || (previousSpread && previousSpread !== p.spread))
    ) {
      void this.reopenWithFlow(p.flow);
      return;
    }

    if (!this.rendition || this.isDestroyed) return;

    const css = typeof document !== "undefined" ? getComputedStyle(document.documentElement) : null;
    const bg = css?.getPropertyValue("--bg").trim() || "#FAFAF7";
    const surface = css?.getPropertyValue("--surface").trim() || "#FFFFFF";
    const text = css?.getPropertyValue("--text").trim() || "#1C1B19";
    const muted = css?.getPropertyValue("--muted").trim() || "#6B6860";
    const border = css?.getPropertyValue("--border").trim() || "#E6E3DC";
    const accent = css?.getPropertyValue("--accent").trim() || "#2F6F5E";

    const fontMap: Record<string, string> = {
      serif: '"Literata", "Noto Serif Devanagari", Georgia, serif',
      sans: 'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans Devanagari", sans-serif',
      mono: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace'
    };

    const isPaginated = p.flow === "paginated";

    this.rendition.themes.fontSize(`${p.fontSize}px`);
    this.rendition.themes.font(fontMap[p.fontFamily] || fontMap.serif!);

    this.rendition.themes.default({
      "*, *::before, *::after": {
        "box-sizing": "border-box"
      },
      "html": {
        "background-color": `${bg} !important`,
        "color": `${text} !important`,
        "-webkit-text-size-adjust": "100%"
      },
      "body": {
        "background-color": `${bg} !important`,
        "color": `${text} !important`,
        "font-family": `${fontMap[p.fontFamily] || fontMap.serif} !important`,
        "font-size": `${p.fontSize}px !important`,
        "line-height": `${p.lineHeight} !important`,
        "letter-spacing": `${p.letterSpacing ?? 0}em !important`,
        "text-align": p.justify ? "justify !important" : "left !important",
        "hyphens": p.hyphenate ? "auto !important" : "manual !important",
        "-webkit-hyphens": p.hyphenate ? "auto !important" : "manual !important",
        "overflow-wrap": "break-word !important",
        "word-break": "normal !important",
        // In scrolled mode we apply horizontal/vertical padding inside the document;
        // in paginated mode, outer host wrapper handles safe zones so epub.js column math never clips!
        ...(isPaginated
          ? {
              "margin": "0 !important",
              "padding": `8px ${Math.max(12, Math.min(p.margin, 32))}px !important`
            }
          : {
              "max-width": `${p.maxWidth ?? 720}px !important`,
              "margin": "0 auto !important",
              "padding": `24px ${p.margin}px 48px ${p.margin}px !important`
            })
      },
      "p, li, dd, blockquote": {
        "color": `${text} !important`,
        "line-height": `${p.lineHeight} !important`,
        "letter-spacing": `${p.letterSpacing ?? 0}em !important`,
        "orphans": "2",
        "widows": "2"
      },
      "p": {
        "margin-top": "0 !important",
        "margin-bottom": `${p.paragraphSpacing ?? 0.5}em !important`
      },
      "h1, h2, h3, h4, h5, h6": {
        "color": `${text} !important`,
        "line-height": "1.28 !important",
        "break-after": "avoid",
        "page-break-after": "avoid"
      },
      // Prevent media, tables, and code blocks from overflowing horizontally or vertically
      "img, svg, video, canvas": {
        "max-width": "100% !important",
        "max-height": isPaginated ? "78vh !important" : "none !important",
        "height": "auto !important",
        "object-fit": "contain !important",
        "display": "block",
        "margin": "0.75em auto !important",
        "break-inside": "avoid !important",
        "page-break-inside": "avoid !important"
      },
      "figure": {
        "max-width": "100% !important",
        "margin": "1em 0 !important",
        "break-inside": "avoid !important"
      },
      "figcaption": {
        "font-size": "0.85em !important",
        "color": `${muted} !important`,
        "text-align": "center !important",
        "margin-top": "0.4em !important"
      },
      "pre": {
        "max-width": "100% !important",
        "overflow-x": "auto !important",
        "white-space": "pre-wrap !important",
        "word-wrap": "break-word !important",
        "background-color": `${surface} !important`,
        "border": `1px solid ${border} !important`,
        "border-radius": "8px !important",
        "padding": "0.75em 1em !important",
        "font-size": "0.88em !important"
      },
      "code": {
        "font-family": `${fontMap.mono} !important`,
        "font-size": "0.9em !important"
      },
      "table": {
        "display": "block !important",
        "max-width": "100% !important",
        "overflow-x": "auto !important",
        "border-collapse": "collapse !important",
        "margin": "1em 0 !important"
      },
      "th, td": {
        "border": `1px solid ${border} !important`,
        "padding": "0.4em 0.6em !important",
        "color": `${text} !important`
      },
      "blockquote": {
        "border-left": `3px solid ${accent} !important`,
        "margin": "1em 0 !important",
        "padding": "0.25em 0 0.25em 1em !important",
        "color": `${muted} !important`
      },
      "a": {
        "color": `${accent} !important`,
        "text-decoration": "underline",
        "text-underline-offset": "2px"
      }
    });
  }

  onRelocate(
    cb: (r: { location: string; percent: number; chapterId?: string; chapterTitle?: string }) => void
  ): void {
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
    if (!this.rendition || !a.location || this.isDestroyed) return;

    const colors: Record<string, string> = {
      yellow: "#FFE58A",
      green: "#B7E4B0",
      blue: "#A9D1F5",
      pink: "#F5B7CC"
    };
    const fill = colors[a.color || "yellow"] || "#FFE58A";

    try {
      this.rendition.annotations.highlight(
        a.location,
        {},
        () => {},
        `hl-${a.id}`,
        { fill, "fill-opacity": "0.45" }
      );
      this.highlightMap.set(a.id, a.location);
    } catch (err) {
      console.warn("EpubEngine addHighlight failed:", err);
    }
  }

  removeHighlight(id: string): void {
    if (!this.rendition || this.isDestroyed) return;
    const cfiRange = this.highlightMap.get(id);
    if (cfiRange) {
      try {
        this.rendition.annotations.remove(cfiRange, "highlight");
        this.highlightMap.delete(id);
      } catch (err) {
        console.warn("EpubEngine removeHighlight failed:", err);
      }
    }
  }

  async *search(q: string): AsyncIterable<{ location: string; excerpt: string }> {
    if (!this.book || this.isDestroyed) return;
    // Iterate through spine items searching text
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const spine = (this.book as any).spine;
    if (!spine || !spine.spineItems) return;

    for (const item of spine.spineItems) {
      if (this.isDestroyed) return;
      await item.load(this.book.load.bind(this.book));
      const results = item.find(q);
      item.unload();
      for (const res of results) {
        yield { location: res.cfi, excerpt: res.excerpt };
      }
    }
  }

  async goToPercent(percent: number): Promise<void> {
    if (!this.rendition || this.isDestroyed) return;
    const fraction = Math.max(0, Math.min(1, percent / 100));
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const locations = (this.book as any)?.locations;
    if (locations && locations.length() > 0) {
      const cfi = locations.cfiFromPercentage(fraction);
      if (cfi) {
        await this.rendition.display(cfi);
        return;
      }
    }
    // Fallback to spine items
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const spine = (this.book as any)?.spine;
    if (spine?.spineItems?.length) {
      const idx = Math.min(
        spine.spineItems.length - 1,
        Math.floor(fraction * spine.spineItems.length)
      );
      const item = spine.spineItems[idx];
      if (item?.href) {
        await this.rendition.display(item.href);
      }
    }
  }

  destroy(): void {
    this.isDestroyed = true;
    if (this.resizeTimer) clearTimeout(this.resizeTimer);
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;

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
