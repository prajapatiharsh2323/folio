import React, { useEffect, useRef, useState } from "react";
import ePub, { Book, Rendition } from "epubjs";
import { sanitizeSectionDoc, sanitizeSerializedHtml } from "../reader/epub-engine.js";

export const EpubSpikeScreen: React.FC = () => {
  const [selectedBook, setSelectedBook] = useState<string>("/fixtures/english-novel.epub");
  const [engine, setEngine] = useState<"epubjs" | "foliate">("epubjs");
  const [fontSize, setFontSize] = useState<number>(18);
  const [fontFamily, setFontFamily] = useState<"serif" | "sans">("serif");
  const [cfi, setCfi] = useState<string>("");
  const [logs, setLogs] = useState<string[]>([]);

  const containerRef = useRef<HTMLDivElement>(null);
  const bookRef = useRef<Book | null>(null);
  const renditionRef = useRef<Rendition | null>(null);

  const addLog = (msg: string) => {
    setLogs((prev) => [`[${new Date().toLocaleTimeString()}] ${msg}`, ...prev.slice(0, 19)]);
  };

  useEffect(() => {
    if (!containerRef.current) return;
    containerRef.current.innerHTML = "";

    if (engine === "epubjs") {
      addLog(`Initializing epub.js with ${selectedBook}`);
      const book = ePub(selectedBook);
      bookRef.current = book;

      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const spineHooks = (book.spine as any)?.hooks;
      if (spineHooks) {
        if (spineHooks.content?.register) spineHooks.content.register(sanitizeSectionDoc);
        if (spineHooks.serialize?.register) spineHooks.serialize.register(sanitizeSerializedHtml);
      }

      const rendition = book.renderTo(containerRef.current, {
        width: "100%",
        height: "100%",
        flow: "paginated",
        spread: "none",
        allowScriptedContent: false
      });
      renditionRef.current = rendition;

      rendition.themes.fontSize(`${fontSize}px`);
      rendition.themes.font(fontFamily === "serif" ? "serif" : "sans-serif");

      rendition.on("relocated", (location: { start?: { cfi?: string } }) => {
        if (location.start?.cfi) {
          setCfi(location.start.cfi);
          addLog(`epub.js relocated: CFI=${location.start.cfi}`);
        }
      });

      rendition.on("selected", (cfiRange: string) => {
        addLog(`epub.js selection CFI: ${cfiRange}`);
        rendition.annotations.highlight(cfiRange, {}, () => {}, "hl-yellow", {
          fill: "yellow",
          "fill-opacity": "0.4"
        });
      });

      rendition.display().catch((err: Error) => {
        addLog(`epub.js error: ${err.message}`);
      });

      return () => {
        book.destroy();
      };
    } else {
      // Foliate Spike
      addLog(`Initializing foliate-js view for ${selectedBook}`);
      const iframe = document.createElement("iframe");
      iframe.style.width = "100%";
      iframe.style.height = "100%";
      iframe.style.border = "none";
      containerRef.current.appendChild(iframe);

      // Foliate view wrapper
      import("foliate-js/view.js").then(({ makeBook }) => {
        fetch(selectedBook)
          .then((res) => res.blob())
          .then(async (blob) => {
            const foliateBook = await makeBook(blob);
            addLog(`foliate-js book opened: ${foliateBook.metadata?.title || "Untitled"}`);
          })
          .catch((err: Error) => {
            addLog(`foliate-js fetch/open error: ${err.message}`);
          });
      }).catch((err: Error) => {
        addLog(`foliate-js module load error: ${err.message}`);
      });

      return () => {};
    }
  }, [selectedBook, engine]);

  const handlePrev = () => {
    if (renditionRef.current) renditionRef.current.prev();
  };

  const handleNext = () => {
    if (renditionRef.current) renditionRef.current.next();
  };

  const handleFontSizeChange = (size: number) => {
    setFontSize(size);
    if (renditionRef.current) {
      renditionRef.current.themes.fontSize(`${size}px`);
      addLog(`Updated font size to ${size}px`);
    }
  };

  const handleFontFamilyChange = (fam: "serif" | "sans") => {
    setFontFamily(fam);
    if (renditionRef.current) {
      renditionRef.current.themes.font(fam === "serif" ? "serif" : "sans-serif");
      addLog(`Updated font family to ${fam}`);
    }
  };

  return (
    <div className="flex flex-col h-full w-full bg-surface">
      {/* Spike Controls Toolbar */}
      <div className="p-4 border-b border-border bg-bg flex flex-wrap gap-3 items-center justify-between text-xs ui-chrome">
        <div className="flex items-center gap-2">
          <span className="font-bold text-accent">Spike Engine:</span>
          <button
            onClick={() => setEngine("epubjs")}
            className={`px-3 py-1.5 rounded-lg border font-medium ${
              engine === "epubjs" ? "bg-accent text-white border-accent" : "bg-surface border-border text-text"
            }`}
          >
            epub.js
          </button>
          <button
            onClick={() => setEngine("foliate")}
            className={`px-3 py-1.5 rounded-lg border font-medium ${
              engine === "foliate" ? "bg-accent text-white border-accent" : "bg-surface border-border text-text"
            }`}
          >
            foliate-js
          </button>
        </div>

        <div className="flex items-center gap-2">
          <span className="font-semibold text-muted">Test File:</span>
          <select
            value={selectedBook}
            onChange={(e) => setSelectedBook(e.target.value)}
            className="px-2 py-1.5 rounded-lg border border-border bg-surface text-text"
          >
            <option value="/fixtures/english-novel.epub">English Novel</option>
            <option value="/fixtures/hindi-book.epub">Hindi Devanagari Book</option>
            <option value="/fixtures/image-book.epub">Image Rich Book</option>
          </select>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={() => handleFontSizeChange(Math.max(12, fontSize - 2))}
            className="px-2 py-1 border border-border rounded bg-surface hover:bg-bg"
          >
            A-
          </button>
          <span className="font-mono">{fontSize}px</span>
          <button
            onClick={() => handleFontSizeChange(Math.min(32, fontSize + 2))}
            className="px-2 py-1 border border-border rounded bg-surface hover:bg-bg"
          >
            A+
          </button>
          <button
            onClick={() => handleFontFamilyChange(fontFamily === "serif" ? "sans" : "serif")}
            className="px-2 py-1 border border-border rounded bg-surface font-semibold"
          >
            {fontFamily}
          </button>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handlePrev}
            className="px-3 py-1.5 rounded-lg bg-surface border border-border hover:bg-bg font-medium"
          >
            ← Prev
          </button>
          <button
            onClick={handleNext}
            className="px-3 py-1.5 rounded-lg bg-surface border border-border hover:bg-bg font-medium"
          >
            Next →
          </button>
        </div>
      </div>

      {/* Reader Container & Diagnostics Pane */}
      <div className="flex-1 flex flex-col md:flex-row min-h-0 overflow-hidden">
        {/* Book Render Target */}
        <div className="flex-1 h-full min-h-[300px] relative bg-white overflow-hidden" ref={containerRef} />

        {/* Live Diagnostics Log */}
        <div className="w-full md:w-80 border-t md:border-t-0 md:border-l border-border bg-bg/50 p-3 flex flex-col h-48 md:h-full overflow-hidden text-xs">
          <h4 className="font-semibold text-text uppercase tracking-wider mb-2">Spike Diagnostics</h4>
          <div className="mb-2 p-2 rounded bg-surface border border-border font-mono text-[11px] truncate">
            CFI: {cfi || "None"}
          </div>
          <div className="flex-1 overflow-y-auto space-y-1 font-mono text-[10px] text-muted">
            {logs.map((log, i) => (
              <div key={i} className="leading-tight">{log}</div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
