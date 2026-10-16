import * as pdfjsLib from "pdfjs-dist/legacy/build/pdf.mjs";
import pdfjsWorkerUrl from "pdfjs-dist/legacy/build/pdf.worker.mjs?url";
import type { Annotation, DocMeta, ReadingPrefs } from "@folio/core";
import type { ReaderEngine } from "./reader-engine.js";

if (typeof window !== "undefined" && !pdfjsLib.GlobalWorkerOptions.workerSrc) {
  pdfjsLib.GlobalWorkerOptions.workerSrc = pdfjsWorkerUrl;
}

export class PdfEngine implements ReaderEngine {
  private pdfDoc: pdfjsLib.PDFDocumentProxy | null = null;
  private containerEl: HTMLElement | null = null;
  private canvasEl: HTMLCanvasElement | null = null;
  private textLayerEl: HTMLDivElement | null = null;
  private currentPage = 1;
  private numPages = 1;
  private currentPrefs: ReadingPrefs | null = null;
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
  private highlights: Annotation[] = [];
  private isRendering = false;
  private resizeObserver: ResizeObserver | null = null;
  private resizeTimer: ReturnType<typeof setTimeout> | null = null;

  private observeContainerResize(el: HTMLElement): void {
    this.resizeObserver?.disconnect();
    if (typeof ResizeObserver === "undefined") return;

    this.resizeObserver = new ResizeObserver((entries) => {
      if (!this.pdfDoc || !this.containerEl) return;
      const entry = entries[0];
      if (!entry) return;
      const { width, height } = entry.contentRect;
      if (width <= 0 || height <= 0) return;

      if (this.resizeTimer) clearTimeout(this.resizeTimer);
      this.resizeTimer = setTimeout(() => {
        if (this.pdfDoc && this.containerEl) {
          this.renderCurrentPage().catch(() => {});
        }
      }, 100);
    });

    this.resizeObserver.observe(el);
  }

  async open(
    _doc: DocMeta,
    el: HTMLElement,
    o: { location?: string; prefs: ReadingPrefs; epubBytes?: Uint8Array; url?: string }
  ): Promise<void> {
    this.destroy();
    this.containerEl = el;
    this.currentPrefs = o.prefs;

    if (!o.epubBytes && !o.url) {
      throw new Error("No PDF source provided to PdfEngine");
    }

    const sourceData = o.epubBytes
      ? { data: o.epubBytes, useSystemFonts: true }
      : { url: o.url, useSystemFonts: true };

    const loadingTask = pdfjsLib.getDocument(sourceData);
    this.pdfDoc = await loadingTask.promise;
    this.numPages = this.pdfDoc.numPages;

    // Parse initial page from location (e.g. 'page-3' or '3')
    if (o.location) {
      const match = o.location.match(/\d+/);
      if (match) {
        const p = parseInt(match[0], 10);
        if (p >= 1 && p <= this.numPages) {
          this.currentPage = p;
        }
      }
    }

    // Setup DOM elements
    el.innerHTML = "";
    el.style.position = "relative";
    el.style.overflow = "auto";
    el.style.display = "flex";
    el.style.flexDirection = "column";
    el.style.alignItems = "center";
    el.style.width = "100%";
    el.style.height = "100%";

    const pageWrapper = document.createElement("div");
    pageWrapper.className = "pdf-page-wrapper relative mx-auto my-auto shadow-md";
    pageWrapper.style.position = "relative";
    pageWrapper.style.transformOrigin = "top center";
    pageWrapper.style.margin = "12px auto";

    const canvas = document.createElement("canvas");
    canvas.className = "pdf-canvas block";
    this.canvasEl = canvas;

    const textLayer = document.createElement("div");
    textLayer.className = "pdf-text-layer absolute inset-0 overflow-hidden pointer-events-auto select-text";
    textLayer.style.position = "absolute";
    textLayer.style.left = "0";
    textLayer.style.top = "0";
    textLayer.style.right = "0";
    textLayer.style.bottom = "0";
    this.textLayerEl = textLayer;

    // Selection listener on text layer
    textLayer.addEventListener("mouseup", () => this.handleTextSelection());
    textLayer.addEventListener("touchend", () => this.handleTextSelection());

    pageWrapper.appendChild(canvas);
    pageWrapper.appendChild(textLayer);
    el.appendChild(pageWrapper);

    this.observeContainerResize(el);
    await this.renderCurrentPage();
  }

  private handleTextSelection(): void {
    const sel = window.getSelection();
    if (!sel || sel.isCollapsed || sel.rangeCount === 0) return;

    const range = sel.getRangeAt(0);
    const quote = range.toString().trim();
    if (!quote) return;

    const rect = range.getBoundingClientRect();
    const nodeText = range.startContainer.textContent || "";
    const startOffset = range.startOffset;
    const endOffset = range.endOffset;
    const prefix = nodeText.substring(Math.max(0, startOffset - 32), startOffset);
    const suffix = nodeText.substring(endOffset, Math.min(nodeText.length, endOffset + 32));

    const location = `page-${this.currentPage}`;
    for (const listener of this.selectionListeners) {
      listener({ quote, prefix, suffix, location, rect });
    }
  }

  private async renderCurrentPage(): Promise<void> {
    if (!this.pdfDoc || !this.canvasEl || !this.containerEl || this.isRendering) return;
    this.isRendering = true;

    try {
      const page = await this.pdfDoc.getPage(this.currentPage);
      const containerWidth = Math.max(280, (this.containerEl.clientWidth || 800) - 24);
      const containerHeight = Math.max(320, (this.containerEl.clientHeight || 600) - 24);
      const unscaledViewport = page.getViewport({ scale: 1.0 });

      const zoomPref = this.currentPrefs?.pdfZoom ?? "fit-width";
      let scale: number;
      if (zoomPref === "fit-page") {
        scale = Math.min(
          containerWidth / unscaledViewport.width,
          containerHeight / unscaledViewport.height
        );
      } else if (typeof zoomPref === "number") {
        scale = (containerWidth / unscaledViewport.width) * zoomPref;
      } else {
        // Default fit-width capped to reading maxWidth
        const maxW = Math.min(containerWidth, this.currentPrefs?.maxWidth ?? 860);
        scale = maxW / unscaledViewport.width;
      }

      const viewport = page.getViewport({ scale: Math.max(scale, 0.4) });

      const canvas = this.canvasEl;
      const context = canvas.getContext("2d");
      if (!context) return;

      const outputScale = window.devicePixelRatio || 1;
      canvas.width = Math.floor(viewport.width * outputScale);
      canvas.height = Math.floor(viewport.height * outputScale);
      canvas.style.width = `${Math.floor(viewport.width)}px`;
      canvas.style.height = `${Math.floor(viewport.height)}px`;

      context.setTransform(outputScale, 0, 0, outputScale, 0, 0);

      // Apply theme background and canvas CSS filter
      let effectiveTheme = this.currentPrefs?.theme || "light";
      if (effectiveTheme === "system") {
        effectiveTheme =
          typeof window !== "undefined" &&
          window.matchMedia?.("(prefers-color-scheme: dark)").matches
            ? "dark"
            : "light";
      }

      if (effectiveTheme === "dark") {
        this.canvasEl.style.filter = "invert(0.92) hue-rotate(180deg) contrast(0.95)";
        context.fillStyle = "#121214";
        context.fillRect(0, 0, viewport.width, viewport.height);
      } else if (effectiveTheme === "true-black") {
        // Same inverted treatment as dark, with a pure black page background
        this.canvasEl.style.filter = "invert(0.92) hue-rotate(180deg) contrast(1.02)";
        context.fillStyle = "#000000";
        context.fillRect(0, 0, viewport.width, viewport.height);
      } else if (effectiveTheme === "warm") {
        this.canvasEl.style.filter = "sepia(0.28) brightness(0.97)";
        context.fillStyle = "#fbf0d9";
        context.fillRect(0, 0, viewport.width, viewport.height);
      } else {
        this.canvasEl.style.filter = "none";
        context.fillStyle = "#ffffff";
        context.fillRect(0, 0, viewport.width, viewport.height);
      }

      await page.render({
        canvasContext: context,
        viewport,
        canvas
      }).promise;

      // Render Text Layer
      if (this.textLayerEl) {
        this.textLayerEl.innerHTML = "";
        this.textLayerEl.style.width = `${Math.floor(viewport.width)}px`;
        this.textLayerEl.style.height = `${Math.floor(viewport.height)}px`;

        const textContent = await page.getTextContent();
        for (const item of textContent.items) {
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          const textItem = item as any;
          if (!textItem.str) continue;

          const span = document.createElement("span");
          span.textContent = textItem.str;
          span.style.position = "absolute";
          span.style.color = "transparent";
          span.style.cursor = "text";
          span.style.userSelect = "text";

          // Calculate transform positioning
          const tx = pdfjsLib.Util.transform(viewport.transform, textItem.transform);
          span.style.left = `${tx[4]}px`;
          span.style.top = `${tx[5] - textItem.height}px`;
          span.style.fontSize = `${textItem.height * scale}px`;
          span.style.fontFamily = textItem.fontName || "sans-serif";

          this.textLayerEl.appendChild(span);
        }

        // Render current page highlights
        this.renderHighlights();
      }

      // Notify relocate listeners
      const percent = Math.round((this.currentPage / this.numPages) * 1000) / 10;
      const location = `page-${this.currentPage}`;
      const chapterTitle = `Page ${this.currentPage} of ${this.numPages}`;
      for (const listener of this.relocateListeners) {
        listener({ location, percent, chapterId: location, chapterTitle });
      }
    } catch (err) {
      console.error("Error rendering PDF page:", err);
    } finally {
      this.isRendering = false;
    }
  }

  private renderHighlights(): void {
    if (!this.textLayerEl) return;
    const pageLoc = `page-${this.currentPage}`;
    const pageHighlights = this.highlights.filter((h) => h.location === pageLoc);

    for (const h of pageHighlights) {
      const mark = document.createElement("div");
      mark.id = `hl-${h.id}`;
      mark.className = `pdf-highlight bg-${h.color || "yellow"}-200/40 rounded absolute pointer-events-none`;
      mark.style.backgroundColor =
        h.color === "green"
          ? "rgba(34, 197, 94, 0.3)"
          : h.color === "blue"
          ? "rgba(59, 130, 246, 0.3)"
          : h.color === "pink"
          ? "rgba(236, 72, 153, 0.3)"
          : "rgba(234, 179, 8, 0.35)";
      this.textLayerEl.appendChild(mark);
    }
  }

  async goTo(location: string): Promise<void> {
    const match = location.match(/\d+/);
    if (!match) return;
    const p = parseInt(match[0], 10);
    if (p >= 1 && p <= this.numPages && p !== this.currentPage) {
      this.currentPage = p;
      await this.renderCurrentPage();
    }
  }

  async next(): Promise<void> {
    if (this.currentPage < this.numPages) {
      this.currentPage++;
      await this.renderCurrentPage();
    }
  }

  async prev(): Promise<void> {
    if (this.currentPage > 1) {
      this.currentPage--;
      await this.renderCurrentPage();
    }
  }

  async getToc(): Promise<{ title: string; location: string; depth: number; percent?: number }[]> {
    if (!this.pdfDoc) return [];
    try {
      const outline = await this.pdfDoc.getOutline();
      if (outline && outline.length > 0) {
        return outline.map((item, index) => ({
          title: item.title,
          location: `page-${index + 1}`,
          depth: 0,
          percent: Math.round((index / Math.max(1, outline.length - 1)) * 1000) / 10
        }));
      }
    } catch {
      // fallback
    }

    // Default to page list
    const toc = [];
    for (let p = 1; p <= this.numPages; p++) {
      toc.push({
        title: `Page ${p}`,
        location: `page-${p}`,
        depth: 0,
        percent: Math.round(((p - 1) / Math.max(1, this.numPages - 1)) * 1000) / 10
      });
    }
    return toc;
  }

  applyPrefs(p: ReadingPrefs): void {
    this.currentPrefs = p;
    if (this.containerEl) {
      this.renderCurrentPage().catch(() => {});
    }
  }

  async goToPercent(percent: number): Promise<void> {
    const fraction = Math.max(0, Math.min(1, percent / 100));
    const targetPage = Math.max(1, Math.min(this.numPages, Math.round(fraction * this.numPages) || 1));
    await this.goTo(`page-${targetPage}`);
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
    }) => void
  ): void {
    this.selectionListeners.push(cb);
  }

  addHighlight(a: Annotation): void {
    this.highlights.push(a);
    this.renderHighlights();
  }

  removeHighlight(id: string): void {
    this.highlights = this.highlights.filter((h) => h.id !== id);
    if (this.textLayerEl) {
      const el = this.textLayerEl.querySelector(`#hl-${id}`);
      if (el) el.remove();
    }
  }

  async *search(q: string): AsyncIterable<{ location: string; excerpt: string }> {
    if (!this.pdfDoc || !q.trim()) return;
    const normQ = q.toLowerCase();

    for (let p = 1; p <= this.numPages; p++) {
      const page = await this.pdfDoc.getPage(p);
      const textContent = await page.getTextContent();
      const pageText = textContent.items
        // eslint-disable-next-line @typescript-eslint/no-explicit-any
        .map((item: any) => item.str || "")
        .join(" ");

      const idx = pageText.toLowerCase().indexOf(normQ);
      if (idx !== -1) {
        const start = Math.max(0, idx - 40);
        const end = Math.min(pageText.length, idx + q.length + 40);
        const excerpt = pageText.substring(start, end);
        yield {
          location: `page-${p}`,
          excerpt: (start > 0 ? "…" : "") + excerpt + (end < pageText.length ? "…" : "")
        };
      }
    }
  }

  highlightSearchMatch(_location: string): void {
    // PDF page navigation already displays the matching page
  }

  clearSearchHighlight(): void {
    // no-op for PDF engine
  }

  destroy(): void {
    if (this.resizeTimer) clearTimeout(this.resizeTimer);
    this.resizeObserver?.disconnect();
    this.resizeObserver = null;
    this.pdfDoc = null;
    this.relocateListeners = [];
    this.selectionListeners = [];
    this.highlights = [];
    if (this.containerEl) {
      this.containerEl.innerHTML = "";
    }
  }
}
