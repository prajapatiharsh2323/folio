import ePub, { Book, Rendition } from "epubjs";
import { sanitizeSectionDoc, sanitizeSerializedHtml, type Annotation, type DocMeta, type ReadingPrefs } from "@folio/core";
import { fileStore } from "../state/library-store.js";
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
  private currentSearchHighlightCfi: string | null = null;
  private systemThemeMediaQuery: MediaQueryList | null = null;
  private systemThemeListener: (() => void) | null = null;

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
          const target = section?.output || output;
          const clean = sanitizeSerializedHtml(target, section);
          if (section) {
            section.output = clean;
          }
          return clean;
        });
      }
    }
  }

  private patchViewManagerSanitizer(rendition: Rendition): void {
    // Defense-in-depth: Patch View.prototype.load to sanitize any HTML string before assigning to iframe.srcdoc
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const viewClass = (rendition as any).manager?.View;
    if (viewClass && viewClass.prototype && !viewClass.prototype.__folioSanitized) {
      viewClass.prototype.__folioSanitized = true;
      const originalLoad = viewClass.prototype.load;
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      viewClass.prototype.load = function (contents: string) {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        const section = (this as any).section;
        const clean = sanitizeSerializedHtml(contents, section);
        return originalLoad.call(this, clean);
      };
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

  private resetRendition(): void {
    if (this.resizeTimer) clearTimeout(this.resizeTimer);
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;

    if (this.systemThemeMediaQuery && this.systemThemeListener) {
      this.systemThemeMediaQuery.removeEventListener("change", this.systemThemeListener);
      this.systemThemeMediaQuery = null;
      this.systemThemeListener = null;
    }

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
    if (this.containerEl) {
      this.containerEl.innerHTML = "";
    }
    this.highlightMap.clear();
    this.chapterMap.clear();
    this.clearSearchHighlight();
  }

  async open(
    _doc: DocMeta,
    el: HTMLElement,
    o: { location?: string; prefs: ReadingPrefs; epubBytes?: Uint8Array; url?: string },
  ): Promise<void> {
    this.resetRendition();
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

    const isWide = typeof window !== "undefined" ? window.innerWidth >= 800 : el.clientWidth >= 800;
    const targetSpread = o.prefs.flow === "paginated" && o.prefs.spread === "auto" && isWide ? "auto" : "none";
    if (o.prefs.flow === "paginated") {
      if (targetSpread === "auto") {
        const baseMax = o.prefs.maxWidth ?? 720;
        el.style.maxWidth = `${Math.max(840, Math.min(1280, baseMax * 1.75))}px`;
      } else {
        el.style.maxWidth = `${o.prefs.maxWidth ?? 720}px`;
      }
    } else {
      el.style.maxWidth = "100%";
    }

    const rendition = book.renderTo(el, {
      width: "100%",
      height: "100%",
      flow: o.prefs.flow === "paginated" ? "paginated" : "scrolled-doc",
      spread: targetSpread,
      allowScriptedContent: false // Critical rule: never run scripts from book or article content
    });
    this.rendition = rendition;
    this.patchViewManagerSanitizer(rendition);
    this.observeContainerResize(el);

    this.applyPrefs(o.prefs);
    this.setupHooksAndListeners(rendition, el);

    // Ensure package & spine are fully parsed before display
    try {
      await book.ready;
      // Re-register serialize security hook after book.ready to ensure it runs AFTER book.replacements()
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const spineHooks = (book.spine as any)?.hooks;
      if (spineHooks?.serialize && typeof spineHooks.serialize.register === "function") {
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        spineHooks.serialize.register((output: string, section: any) => {
          const target = section?.output || output;
          const clean = sanitizeSerializedHtml(target, section);
          if (section) {
            section.output = clean;
          }
          return clean;
        });
      }
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
    const currLoc = this.safeGetCurrentLocation();
    if (!currLoc?.start?.cfi) {
      console.warn("EpubEngine: rendition.currentLocation() has no CFI after display().");
    } else {
      this.lastRelocatedCfi = currLoc.start.cfi;
    }

    // Cache and load locations asynchronously without blocking UI
    const cacheKey = _doc.contentHash || _doc.id;
    const cachePath = `library/cache/locations/${cacheKey}.json`;

    book.ready
      .then(async () => {
        if (this.isDestroyed || !this.book) return;
        try {
          if (await fileStore.exists(cachePath)) {
            const cachedLocations = await fileStore.readText(cachePath);
            if (cachedLocations && this.book?.locations) {
              this.book.locations.load(cachedLocations);
              this.dispatchLocationUpdate();
              return;
            }
          }
        } catch {
          // ignore cache read failure
        }

        if (this.book?.locations) {
          await this.book.locations.generate(1024);
          if (this.isDestroyed || !this.book) return;
          try {
            const serialized = this.book.locations.save();
            if (serialized) {
              await fileStore.write(cachePath, serialized);
            }
          } catch {
            // ignore cache write failure
          }
          this.dispatchLocationUpdate();
        }
      })
      .catch((err) => {
        console.warn("EpubEngine locations generation:", err);
      });
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  private safeGetCurrentLocation(): any {
    // Rendition's currentLocation() calls this.manager.currentLocation().
    // Before rendition.display() finishes, manager is undefined and will throw TypeError.
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    if (!this.rendition || !(this.rendition as any).manager) return null;
    try {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      return (this.rendition as any).currentLocation?.() || null;
    } catch {
      return null;
    }
  }

  private dispatchLocationUpdate(): void {
    if (this.isDestroyed || !this.rendition || !this.book?.locations) return;
    const curr = this.safeGetCurrentLocation();
    if (curr?.start?.cfi) {
      const cfi = curr.start.cfi;
      this.lastRelocatedCfi = cfi;
      const p = this.book.locations.percentageFromCfi(cfi);
      if (p !== undefined && p !== null && !isNaN(p)) {
        const percent = Math.round(p * 1000) / 10;
        const chapterTitle = this.lookupChapterTitle(curr?.start?.href || "");
        for (const listener of this.relocateListeners) {
          listener({ location: cfi, percent, chapterId: curr?.start?.href, chapterTitle });
        }
      }
    }
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

      // Inject host @font-face rules into iframe head (R-06)
      try {
        let fontCss = "";
        for (const sheet of Array.from(document.styleSheets)) {
          try {
            for (const rule of Array.from(sheet.cssRules || [])) {
              if (rule.constructor.name === "CSSFontFaceRule" || rule instanceof CSSFontFaceRule) {
                fontCss += rule.cssText + "\n";
              }
            }
          } catch {
            // cross-origin stylesheet protection
          }
        }
        if (fontCss && doc.head && !doc.head.querySelector("style[data-folio-fonts]")) {
          const styleEl = doc.createElement("style");
          styleEl.setAttribute("data-folio-fonts", "true");
          styleEl.textContent = fontCss;
          doc.head.appendChild(styleEl);
        }
      } catch {
        // fallback font injection guard
      }

      // Forward keydown from iframe to host container (R-09)
      doc.addEventListener("keydown", (e: KeyboardEvent) => {
        el.dispatchEvent(
          new CustomEvent("folio:keydown", {
            bubbles: true,
            detail: {
              key: e.key,
              code: e.code,
              shiftKey: e.shiftKey,
              altKey: e.altKey,
              ctrlKey: e.ctrlKey,
              metaKey: e.metaKey
            }
          })
        );
      });

      let touchStartX = 0;
      let touchStartY = 0;
      let hadSelectionOnPointerDown = false;

      doc.addEventListener(
        "touchstart",
        (e: TouchEvent) => {
          const sel = doc.getSelection();
          hadSelectionOnPointerDown = !!(sel && sel.toString().trim().length > 0);
          if (e.touches[0]) {
            touchStartX = e.touches[0].clientX;
            touchStartY = e.touches[0].clientY;
          }
          el.dispatchEvent(new CustomEvent("folio:touch-start", { bubbles: true }));
        },
        { passive: true }
      );

      doc.addEventListener("mousedown", () => {
        const sel = doc.getSelection();
        hadSelectionOnPointerDown = !!(sel && sel.toString().trim().length > 0);
      });

      doc.addEventListener(
        "touchend",
        (e: TouchEvent) => {
          el.dispatchEvent(new CustomEvent("folio:touch-end", { bubbles: true }));
          const sel = doc.getSelection();
          const hasSelection = sel && sel.toString().trim().length > 0;
          if (hasSelection || hadSelectionOnPointerDown) {
            return;
          }
          // Horizontal swipe turns pages only in paginated mode
          if (this.currentPrefs?.flow === "paginated" && e.changedTouches[0]) {
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
        const anchor = (e.target as HTMLElement)?.closest("a");
        if (anchor) {
          const href = anchor.getAttribute("href");
          if (href) {
            if (href.startsWith("http://") || href.startsWith("https://")) {
              e.preventDefault();
              window.open(href, "_blank", "noopener,noreferrer");
              return;
            }
            // For internal links, dispatch folio:link-jump event so ReaderScreen can recordJump()
            el.dispatchEvent(new CustomEvent("folio:link-jump", { bubbles: true, detail: { href } }));
          }
          return;
        }
        const sel = doc.getSelection();
        if (sel && sel.toString().trim().length > 0) {
          return;
        }
        // If there was an active selection dismissed by this click, do NOT turn page or toggle controls
        if (hadSelectionOnPointerDown) {
          hadSelectionOnPointerDown = false;
          el.dispatchEvent(new CustomEvent("folio:clear-selection", { bubbles: true }));
          return;
        }
        // Dismiss any existing floating toolbar on page click
        el.dispatchEvent(new CustomEvent("folio:clear-selection", { bubbles: true }));

        // In scrolled mode, clicks should only trigger middle-tap, never page turn
        if (this.currentPrefs?.flow !== "paginated") {
          el.dispatchEvent(new CustomEvent("folio:middle-tap", { bubbles: true }));
          return;
        }

        // Viewport width for accurate tap zones regardless of multi-column chapter width (R-08)
        const frameEl = (doc.defaultView?.frameElement as HTMLElement | null) || el.querySelector("iframe");
        const viewportWidth = frameEl ? frameEl.clientWidth : (el.clientWidth || window.innerWidth);
        const x = e.clientX;
        if (x < viewportWidth / 3) {
          this.prev();
        } else if (x > (2 * viewportWidth) / 3) {
          this.next();
        } else {
          el.dispatchEvent(new CustomEvent("folio:middle-tap", { bubbles: true }));
        }
      });

      // Forward mouse wheel scrolling from iframe to scrollable container or page navigation
      let lastPageTurnTime = 0;
      doc.addEventListener(
        "wheel",
        (e: WheelEvent) => {
          let dy = e.deltaY;
          let dx = e.deltaX;
          // Normalize Windows external mouse wheel line increments (deltaMode 1) and page increments (deltaMode 2)
          if (e.deltaMode === 1) {
            dy *= 28;
            dx *= 28;
          } else if (e.deltaMode === 2) {
            dy *= window.innerHeight || 800;
            dx *= window.innerWidth || 1024;
          }

          if (this.currentPrefs?.flow !== "paginated") {
            const scroller = (el.querySelector(".epub-container") as HTMLElement | null) || el;
            scroller.scrollTop += dy;
            scroller.scrollLeft += dx;
          } else {
            const now = Date.now();
            if (now - lastPageTurnTime > 350) {
              if (dy > 40 || dx > 40) {
                lastPageTurnTime = now;
                this.next();
              } else if (dy < -40 || dx < -40) {
                lastPageTurnTime = now;
                this.prev();
              }
            }
          }
        },
        { passive: true }
      );

      // Track scroll progress on .epub-container for scrolled-flow continuity affordance (Phase 3)
      if (this.currentPrefs?.flow !== "paginated") {
        const attachScrollTracker = () => {
          const scroller = (el.querySelector(".epub-container") as HTMLElement | null) || el;
          if (!scroller) return;
          const checkScroll = () => {
            const scrollBottom = scroller.scrollTop + scroller.clientHeight;
            const isNearBottom =
              scroller.scrollHeight > 0 && scrollBottom >= scroller.scrollHeight - 80;
            el.dispatchEvent(
              new CustomEvent("folio:scroll-progress", {
                bubbles: true,
                detail: {
                  isNearBottom,
                  scrollTop: scroller.scrollTop,
                  scrollHeight: scroller.scrollHeight
                }
              })
            );
          };

          // Attach listener if not already bound
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          if (!(scroller as any).__folioScrollTracker) {
            let lastScrollCheck = 0;
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            (scroller as any).__folioScrollTracker = () => {
              const now = Date.now();
              if (now - lastScrollCheck < 80) return;
              lastScrollCheck = now;
              checkScroll();
            };
            // eslint-disable-next-line @typescript-eslint/no-explicit-any
            scroller.addEventListener("scroll", (scroller as any).__folioScrollTracker, { passive: true });
          }

          // Initial check immediately so short chapters report bottom continuity right away
          checkScroll();
          setTimeout(checkScroll, 250);
        };
        attachScrollTracker();
        setTimeout(attachScrollTracker, 150);
      }
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

    const locToRestore = targetLocation || this.lastRelocatedCfi || this.safeGetCurrentLocation()?.start?.cfi;

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

    const isWide = typeof window !== "undefined" ? window.innerWidth >= 800 : ((this.containerEl.parentElement?.clientWidth ?? 0) >= 800);
    const targetSpread = flow === "paginated" && this.currentPrefs?.spread === "auto" && isWide ? "auto" : "none";

    // Set container maxWidth directly so epub.js measures the target column geometry correctly
    if (flow === "paginated") {
      if (targetSpread === "auto") {
        const baseMax = this.currentPrefs?.maxWidth ?? 720;
        this.containerEl.style.maxWidth = `${Math.max(840, Math.min(1280, baseMax * 1.75))}px`;
      } else {
        this.containerEl.style.maxWidth = `${this.currentPrefs?.maxWidth ?? 720}px`;
      }
    } else {
      this.containerEl.style.maxWidth = "100%";
    }

    const rendition = this.book.renderTo(this.containerEl, {
      width: "100%",
      height: "100%",
      flow: flow === "paginated" ? "paginated" : "scrolled-doc",
      spread: targetSpread,
      allowScriptedContent: false
    });
    this.rendition = rendition;
    this.patchViewManagerSanitizer(rendition);
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
      if (this.currentPrefs?.flow !== "paginated") {
        const scroller = (this.containerEl?.querySelector(".epub-container") as HTMLElement | null) || this.containerEl;
        if (scroller && scroller.scrollHeight > scroller.clientHeight) {
          const atBottom = scroller.scrollTop + scroller.clientHeight >= scroller.scrollHeight - 24;
          if (!atBottom) {
            scroller.scrollBy({ top: scroller.clientHeight * 0.85, behavior: "smooth" });
            return;
          }
        }
      }
      await this.rendition.next();
    }
  }

  async prev(): Promise<void> {
    if (this.rendition && !this.isDestroyed) {
      if (this.currentPrefs?.flow !== "paginated") {
        const scroller = (this.containerEl?.querySelector(".epub-container") as HTMLElement | null) || this.containerEl;
        if (scroller && scroller.scrollHeight > scroller.clientHeight) {
          const atTop = scroller.scrollTop <= 24;
          if (!atTop) {
            scroller.scrollBy({ top: -scroller.clientHeight * 0.85, behavior: "smooth" });
            return;
          }
        }
      }
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
          const cleanHref = item.href.split("#")[0] || "";

          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const spine = (this.book as any)?.spine;
          const section = spine?.get ? spine.get(cleanHref) : null;
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const locations = (this.book as any)?.locations;

          // Compute accurate percentage from section start CFI (Phase 4)
          if (locations && locations.length() > 0 && section?.cfiBase) {
            try {
              const startCfi = `epubcfi(${section.cfiBase}!/4/2/1:0)`;
              const p = locations.percentageFromCfi(startCfi);
              if (p !== undefined && p !== null && !isNaN(p)) {
                percent = Math.round(p * 1000) / 10;
              }
            } catch {}
          }

          // Fallback to spine position fraction until locations are loaded
          if (percent === undefined && section && spine?.spineItems?.length) {
            const frac = section.index / spine.spineItems.length;
            percent = Math.round(frac * 1000) / 10;
          }

          items.push({
            title,
            location: item.href,
            depth,
            percent
          });
          if (item.href && title) {
            this.chapterMap.set(item.href, title);
            if (cleanHref) this.chapterMap.set(cleanHref, title);
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

    if (this.containerEl) {
      if (p.flow === "paginated") {
        const isWide = typeof window !== "undefined" ? window.innerWidth >= 800 : ((this.containerEl.parentElement?.clientWidth ?? 0) >= 800);
        const targetSpread = p.spread === "auto" && isWide;
        this.containerEl.style.maxWidth = targetSpread
          ? `${Math.max(840, Math.min(1280, (p.maxWidth ?? 720) * 1.75))}px`
          : `${p.maxWidth ?? 720}px`;
      } else {
        this.containerEl.style.maxWidth = "100%";
      }
    }

    // Capture current location to prevent reading position drift during typography changes (R-07)
    // Only capture if rendition has already been displayed (has manager) to prevent initial open crash
    const anchorCfi = this.lastRelocatedCfi || this.safeGetCurrentLocation()?.start?.cfi;

    // Dynamic OS system theme listener (R-04)
    if (typeof window !== "undefined" && window.matchMedia) {
      if (p.theme === "system") {
        if (!this.systemThemeMediaQuery) {
          this.systemThemeMediaQuery = window.matchMedia("(prefers-color-scheme: dark)");
          this.systemThemeListener = () => {
            if (this.currentPrefs?.theme === "system" && !this.isDestroyed) {
              this.applyPrefs(this.currentPrefs);
            }
          };
          this.systemThemeMediaQuery.addEventListener("change", this.systemThemeListener);
        }
      } else if (this.systemThemeMediaQuery && this.systemThemeListener) {
        this.systemThemeMediaQuery.removeEventListener("change", this.systemThemeListener);
        this.systemThemeMediaQuery = null;
        this.systemThemeListener = null;
      }
    }

    // Canonical theme token values independent of host DOM timing (R-04)
    const THEME_COLORS: Record<string, { bg: string; surface: string; text: string; muted: string; border: string; accent: string }> = {
      light: {
        bg: "#FAFAF7",
        surface: "#FFFFFF",
        text: "#1C1B19",
        muted: "#6B6860",
        border: "#E6E3DC",
        accent: "#2F6F5E"
      },
      warm: {
        bg: "#F4ECD8",
        surface: "#FBF5E6",
        text: "#3B3226",
        muted: "#7A6C58",
        border: "#E0D4B8",
        accent: "#8A5A2B"
      },
      dark: {
        bg: "#121214",
        surface: "#1C1C20",
        text: "#E6E3DC",
        muted: "#9A968C",
        border: "#2C2C32",
        accent: "#7FC8B2"
      }
    };

    let activeThemeKey = p.theme || "light";
    if (activeThemeKey === "system") {
      activeThemeKey = typeof window !== "undefined" && window.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
    }
    const themeTokens = THEME_COLORS[activeThemeKey] ?? THEME_COLORS.light!;
    const bg = themeTokens.bg;
    const surface = themeTokens.surface;
    const text = themeTokens.text;
    const muted = themeTokens.muted;
    const border = themeTokens.border;
    const accent = themeTokens.accent;

    const fontMap: Record<string, string> = {
      serif: '"Literata", "Noto Serif Devanagari", Georgia, serif',
      sans: 'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans Devanagari", sans-serif',
      mono: 'ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace',
      publisher: 'inherit'
    };

    const isPaginated = p.flow === "paginated";

    this.rendition.themes.fontSize(`${p.fontSize}px`);
    if (p.fontFamily !== "publisher") {
      this.rendition.themes.font(fontMap[p.fontFamily] || fontMap.serif!);
    }

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
        ...(p.fontFamily !== "publisher" ? { "font-family": `${fontMap[p.fontFamily] || fontMap.serif} !important` } : {}),
        "font-size": `${p.fontSize}px !important`,
        "line-height": `${p.lineHeight} !important`,
        "letter-spacing": `${p.letterSpacing ?? 0}em !important`,
        "text-align": p.justify ? "justify !important" : "left !important",
        "hyphens": p.hyphenate ? "auto !important" : "manual !important",
        "-webkit-hyphens": p.hyphenate ? "auto !important" : "manual !important",
        "overflow-wrap": "break-word !important",
        "word-break": "normal !important",
        ...(isPaginated
          ? {
              "margin": "0 !important",
              "padding": "0 !important"
            }
          : {
              "max-width": `${p.maxWidth ?? 720}px !important`,
              "margin": "0 auto !important",
              "padding": `24px ${p.margin}px 48px ${p.margin}px !important`
            })
      },
      // Deep overrides across text nodes so publisher classes do not block user prefs (R-03, R-04)
      ...(p.fontFamily !== "publisher"
        ? {
            "body, p, li, dd, dt, blockquote, div, span, a, em, strong, b, i": {
              "color": `${text} !important`,
              "font-family": `${fontMap[p.fontFamily] || fontMap.serif} !important`
            }
          }
        : {
            "body, p, li, dd, dt, blockquote, div, span, a, em, strong, b, i": {
              "color": `${text} !important`
            }
          }),
      "p, li, dd, blockquote": {
        "color": `${text} !important`,
        "line-height": `${p.lineHeight} !important`,
        "letter-spacing": `${p.letterSpacing ?? 0}em !important`,
        "text-align": p.justify ? "justify !important" : "left !important",
        "hyphens": p.hyphenate ? "auto !important" : "manual !important",
        "-webkit-hyphens": p.hyphenate ? "auto !important" : "manual !important",
        "orphans": "2",
        "widows": "2"
      },
      "p": {
        "margin-top": "0 !important",
        "margin-bottom": `${p.paragraphSpacing ?? 0.5}em !important`
      },
      // Prevent publisher inline elements and divs from having glaring white backgrounds in dark/warm mode
      "body *:not(pre):not(code):not(kbd):not(mark)": {
        "background-color": "transparent !important"
      },
      "h1, h2, h3, h4, h5, h6": {
        "color": `${text} !important`,
        "line-height": "1.28 !important",
        "break-after": "avoid",
        "page-break-after": "avoid"
      },
      // Prevent media, tables, and code blocks from overflowing horizontally or vertically while preserving inline images
      "img, svg, video, canvas": {
        "max-width": "100% !important",
        "max-height": isPaginated ? "78vh !important" : "none !important",
        "height": "auto !important",
        "object-fit": "contain !important"
      },
      "img:not([width='1em']):not([height='1em'])": {
        "display": "block",
        "margin": "0.75em auto !important",
        "break-inside": "avoid !important",
        "page-break-inside": "avoid !important"
      },
      "img[width='1em'], img[height='1em'], img.inline, span img": {
        "display": "inline-block !important",
        "margin": "0 0.1em !important"
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
        "max-width": "100% !important",
        "border-collapse": "collapse !important",
        "margin": "1em 0 !important",
        "font-size": "0.9em !important",
        "word-break": "break-word"
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

    // Re-anchor to current CFI to prevent reading position drift (R-07)
    // Only re-display if rendition has already completed an initial display (has manager)
    if (anchorCfi && (this.rendition as any)?.manager && !this.isDestroyed) {
      try {
        this.rendition.display(anchorCfi).catch(() => {});
      } catch {
        // ignore
      }
    }
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
    const isDark = this.currentPrefs?.theme === "dark" || (
      this.currentPrefs?.theme === "system" &&
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-color-scheme: dark)").matches
    );

    try {
      this.rendition.annotations.highlight(
        a.location,
        { annotationId: a.id },
        (e: MouseEvent) => {
          e.stopPropagation();
          const frameEl = this.containerEl?.querySelector("iframe");
          const frameRect = frameEl?.getBoundingClientRect() ?? new DOMRect(0, 0, 0, 0);
          this.containerEl?.dispatchEvent(
            new CustomEvent("folio:highlight-click", {
              bubbles: true,
              detail: {
                annotationId: a.id,
                rect: new DOMRect(
                  frameRect.left + (e.clientX || 0),
                  frameRect.top + (e.clientY || 0),
                  0,
                  0
                )
              }
            })
          );
        },
        `hl-${a.id}`,
        {
          fill,
          "fill-opacity": isDark ? "0.65" : "0.45",
          "mix-blend-mode": isDark ? "screen" : "multiply"
        }
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

  highlightSearchMatch(location: string): void {
    if (!this.rendition || this.isDestroyed) return;
    this.clearSearchHighlight();
    try {
      this.currentSearchHighlightCfi = location;
      const isDark =
        (this.currentPrefs?.theme || "system") === "dark" ||
        ((this.currentPrefs?.theme || "system") === "system" &&
          typeof window !== "undefined" &&
          window.matchMedia("(prefers-color-scheme: dark)").matches);

      this.rendition.annotations.highlight(
        location,
        {},
        undefined,
        "folio-search-match",
        {
          fill: isDark ? "#f59e0b" : "#fbbf24",
          "fill-opacity": isDark ? "0.65" : "0.5",
          "mix-blend-mode": isDark ? "screen" : "multiply"
        }
      );
    } catch (err) {
      console.warn("EpubEngine highlightSearchMatch failed:", err);
    }
  }

  clearSearchHighlight(): void {
    if (!this.rendition || !this.currentSearchHighlightCfi || this.isDestroyed) return;
    try {
      this.rendition.annotations.remove(this.currentSearchHighlightCfi, "highlight");
    } catch {
      // ignore
    }
    this.currentSearchHighlightCfi = null;
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
    this.resetRendition();
    this.relocateListeners = [];
    this.selectionListeners = [];
  }
}
