import React, { useEffect, useRef, useState, useCallback } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import {
  ArrowLeft,
  Type,
  List,
  Sun,
  Moon,
  Coffee,
  ChevronLeft,
  ChevronRight,
  Maximize2,
  Minimize2,
  Bookmark,
  Copy,
  MessageSquare,
  Check
} from "lucide-react";
import { EpubEngine } from "../reader/epub-engine.js";
import { PdfEngine } from "../reader/pdf-engine.js";
import type { ReaderEngine } from "../reader/reader-engine.js";
import {
  useLibraryStore,
  useReaderStore,
  fileStore,
  libraryManager
} from "../state/library-store.js";
import type { DocMeta, Annotation } from "@folio/core";

interface SelectionData {
  quote: string;
  prefix: string;
  suffix: string;
  location: string;
  rect: DOMRect;
  chapterId?: string;
}

export const ReaderScreen: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const location = useLocation();

  const [doc, setDoc] = useState<DocMeta | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [tocOpen, setTocOpen] = useState(false);
  const [typographyOpen, setTypographyOpen] = useState(false);
  const [tocItems, setTocItems] = useState<{ title: string; location: string; depth: number }[]>([]);
  const [currentPercent, setCurrentPercent] = useState<number>(0);
  const [currentCfi, setCurrentCfi] = useState<string>("");
  const [isFullscreen, setIsFullscreen] = useState(false);

  // Selection & Highlight Toolbar State (Section 7)
  const [selection, setSelection] = useState<SelectionData | null>(null);
  const [noteInputOpen, setNoteInputOpen] = useState(false);
  const [noteText, setNoteText] = useState("");
  const [copiedFeedback, setCopiedFeedback] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const engineRef = useRef<ReaderEngine | null>(null);

  const { prefs, setPrefs } = useReaderStore();
  const libraryDocs = useLibraryStore((state) => state.docs);

  // Parse location and query parameters
  const queryParams = new URLSearchParams(location.search);
  const targetLoc = queryParams.get("loc");
  const targetChapter = queryParams.get("chapter");

  // Load document metadata and saved progress
  useEffect(() => {
    async function load() {
      let found = libraryDocs.find((d) => d.slug === slug);
      if (!found) {
        const metaPath = `library/docs/${slug}/meta.json`;
        if (await fileStore.exists(metaPath)) {
          found = JSON.parse(await fileStore.readText(metaPath)) as DocMeta;
        }
      }
      if (found) {
        setDoc(found);
      }
    }
    load();
  }, [slug, libraryDocs]);

  // Keep screen awake using Screen Wake Lock API (Section 7)
  useEffect(() => {
    let wakeLock: { release: () => Promise<void> } | null = null;
    if (
      "wakeLock" in navigator &&
      (
        navigator as unknown as {
          wakeLock: { request: (type: string) => Promise<{ release: () => Promise<void> }> };
        }
      ).wakeLock
    ) {
      (
        navigator as unknown as {
          wakeLock: { request: (type: string) => Promise<{ release: () => Promise<void> }> };
        }
      ).wakeLock
        .request("screen")
        .then((lock) => {
          wakeLock = lock;
        })
        .catch(() => {});
    }
    return () => {
      if (wakeLock) {
        wakeLock.release().catch(() => {});
      }
    };
  }, []);

  // Debounced progress saving (1.5s after change as specified in Section 7)
  const saveProgressDebounced = useCallback(
    (locStr: string, percent: number) => {
      if (!doc) return;
      const progress = {
        docId: doc.id,
        location: locStr,
        percent,
        updatedAt: new Date().toISOString()
      };
      libraryManager.saveProgress(progress).catch(console.error);
    },
    [doc]
  );

  useEffect(() => {
    if (!currentCfi) return;
    const timer = setTimeout(() => {
      saveProgressDebounced(currentCfi, currentPercent);
    }, 1500);

    return () => clearTimeout(timer);
  }, [currentCfi, currentPercent, saveProgressDebounced]);

  // Initialize Engine (EpubEngine or PdfEngine)
  useEffect(() => {
    if (!doc || !containerRef.current) return;

    let engine: ReaderEngine;
    if (doc.sourceFormat === "pdf") {
      engine = new PdfEngine();
    } else {
      engine = new EpubEngine();
    }
    engineRef.current = engine;

    async function init() {
      if (!doc || !containerRef.current) return;
      const saved = await libraryManager.getProgress(doc.id);

      // Determine initial location: targetLoc -> targetChapter -> saved -> first page
      let initialLocation = targetLoc || undefined;
      if (!initialLocation && targetChapter) {
        const foundCh = doc.chapters.find((c) => c.id === targetChapter);
        if (foundCh) initialLocation = foundCh.href;
      }
      if (!initialLocation) {
        initialLocation = saved?.location;
      }

      let epubBytes: Uint8Array | undefined;
      let url: string | undefined;

      const readingPath = doc.files.reading
        ? `library/docs/${doc.slug}/${doc.files.reading}`
        : null;
      const origPath = doc.files.original
        ? `library/docs/${doc.slug}/${doc.files.original}`
        : null;

      if (readingPath && (await fileStore.exists(readingPath))) {
        epubBytes = await fileStore.read(readingPath);
      } else if (origPath && (await fileStore.exists(origPath))) {
        epubBytes = await fileStore.read(origPath);
      } else {
        url = `/fixtures/${doc.files.original || "english-novel.epub"}`;
      }

      await engine.open(doc, containerRef.current, {
        location: initialLocation,
        prefs,
        epubBytes,
        url
      });

      engine.onRelocate((r) => {
        setCurrentCfi(r.location);
        setCurrentPercent(r.percent);
      });

      engine.onSelection((s) => {
        setSelection(s);
        setNoteInputOpen(false);
        setNoteText("");
      });

      // Load existing annotations
      const existingAnn = await libraryManager.getAnnotations(doc.id);
      for (const ann of existingAnn) {
        engine.addHighlight(ann);
      }

      const toc = await engine.getToc();
      setTocItems(toc);
    }

    init();

    return () => {
      engine.destroy();
    };
  }, [doc, targetLoc, targetChapter]);

  // Apply typography updates dynamically
  useEffect(() => {
    if (engineRef.current) {
      engineRef.current.applyPrefs(prefs);
    }
    document.documentElement.setAttribute("data-theme", prefs.theme);
  }, [prefs]);

  // Key navigation (Section 7)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (noteInputOpen) return;
      if (e.key === "ArrowRight" || e.key === "PageDown" || e.key === " ") {
        engineRef.current?.next();
      } else if (e.key === "ArrowLeft" || e.key === "PageUp") {
        engineRef.current?.prev();
      } else if (e.key === "t" || e.key === "T") {
        setTocOpen((prev) => !prev);
      } else if (e.key === "Escape") {
        setSelection(null);
        setTocOpen(false);
        setTypographyOpen(false);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [noteInputOpen]);

  const handleContainerClick = (e: React.MouseEvent<HTMLDivElement>) => {
    // If text was selected or clicking inside selection toolbar, don't trigger page turn
    if (selection) {
      setSelection(null);
      return;
    }

    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const width = rect.width;

    if (x < width / 3) {
      engineRef.current?.prev();
    } else if (x > (2 * width) / 3) {
      engineRef.current?.next();
    } else {
      setControlsVisible((prev) => !prev);
    }
  };

  const handleSaveHighlight = async (color: "yellow" | "green" | "blue" | "pink") => {
    if (!doc || !selection) return;

    // Haptic feedback (10ms) Section 9 Checklist #6
    if ("vibrate" in navigator) {
      try {
        navigator.vibrate(10);
      } catch {
        // ignore
      }
    }

    const annotation: Annotation = {
      id: crypto.randomUUID(),
      docId: doc.id,
      quote: selection.quote,
      prefix: selection.prefix,
      suffix: selection.suffix,
      location: selection.location,
      kind: noteText ? "note" : "highlight",
      color,
      note: noteText.trim() || undefined,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    await libraryManager.addAnnotation(annotation);
    engineRef.current?.addHighlight(annotation);

    setSelection(null);
    setNoteInputOpen(false);
    setNoteText("");
  };

  const handleCopyQuote = async () => {
    if (!selection) return;
    try {
      await navigator.clipboard.writeText(selection.quote);
      setCopiedFeedback(true);
      setTimeout(() => setCopiedFeedback(false), 1500);
    } catch {
      // ignore
    }
  };

  const toggleFullscreen = () => {
    if (!document.fullscreenElement) {
      document.documentElement.requestFullscreen().catch(() => {});
      setIsFullscreen(true);
    } else {
      document.exitFullscreen().catch(() => {});
      setIsFullscreen(false);
    }
  };

  if (!doc) {
    return (
      <div className="flex h-screen items-center justify-center bg-bg text-muted">
        Loading document...
      </div>
    );
  }

  return (
    <div className="relative h-screen w-screen overflow-hidden select-none bg-bg text-text flex flex-col">
      {/* Top auto-hiding header */}
      <header
        className={`fixed top-0 left-0 right-0 h-14 bg-surface/95 backdrop-blur-sm border-b border-border z-40 px-4 flex items-center justify-between transition-transform duration-200 ${
          controlsVisible ? "translate-y-0" : "-translate-y-full"
        }`}
      >
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate("/")}
            className="p-2 rounded-lg hover:bg-bg transition-colors text-muted hover:text-text"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <div className="overflow-hidden max-w-xs sm:max-w-md">
            <h3 className="font-semibold text-sm truncate text-text">{doc.title}</h3>
            <p className="text-[11px] text-muted truncate">
              {doc.author || "Folio Reader"} · {doc.sourceFormat.toUpperCase()}
            </p>
          </div>
        </div>

        <div className="flex items-center gap-1">
          <button
            onClick={() => setTocOpen(true)}
            title="Table of Contents"
            className="p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            <List className="w-5 h-5" />
          </button>
          <button
            onClick={() => setTypographyOpen(true)}
            title="Typography & Appearance"
            className="p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            <Type className="w-5 h-5" />
          </button>
          <button
            onClick={toggleFullscreen}
            title="Toggle Fullscreen"
            className="hidden sm:inline-flex p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            {isFullscreen ? <Minimize2 className="w-5 h-5" /> : <Maximize2 className="w-5 h-5" />}
          </button>
        </div>
      </header>

      {/* Main reading canvas with 3-column tap interaction */}
      <main
        className="flex-1 relative h-full w-full overflow-hidden"
        onClick={handleContainerClick}
      >
        <div ref={containerRef} className="h-full w-full" />

        {/* Text Selection Toolbar (Section 7) */}
        {selection && (
          <div
            onClick={(e) => e.stopPropagation()}
            className="fixed z-50 transform -translate-x-1/2 bg-surface border border-border rounded-xl shadow-2xl p-2 flex flex-col gap-2 transition-all animate-in fade-in zoom-in-95"
            style={{
              left: `${Math.min(Math.max(selection.rect.left + selection.rect.width / 2, 120), window.innerWidth - 120)}px`,
              top: `${Math.max(selection.rect.top - 60, 20)}px`
            }}
          >
            {!noteInputOpen ? (
              <div className="flex items-center gap-1.5">
                {/* 4 Highlight colors */}
                <button
                  onClick={() => handleSaveHighlight("yellow")}
                  title="Highlight Yellow"
                  className="w-7 h-7 rounded-full bg-amber-400 hover:scale-110 transition-transform shadow-xs flex items-center justify-center border border-amber-500/30"
                />
                <button
                  onClick={() => handleSaveHighlight("green")}
                  title="Highlight Green"
                  className="w-7 h-7 rounded-full bg-emerald-400 hover:scale-110 transition-transform shadow-xs flex items-center justify-center border border-emerald-500/30"
                />
                <button
                  onClick={() => handleSaveHighlight("blue")}
                  title="Highlight Blue"
                  className="w-7 h-7 rounded-full bg-sky-400 hover:scale-110 transition-transform shadow-xs flex items-center justify-center border border-sky-500/30"
                />
                <button
                  onClick={() => handleSaveHighlight("pink")}
                  title="Highlight Pink"
                  className="w-7 h-7 rounded-full bg-pink-400 hover:scale-110 transition-transform shadow-xs flex items-center justify-center border border-pink-500/30"
                />

                <div className="w-px h-5 bg-border mx-1" />

                {/* Add Note Button */}
                <button
                  onClick={() => setNoteInputOpen(true)}
                  className="px-2.5 py-1.5 rounded-lg hover:bg-bg text-text text-xs font-medium flex items-center gap-1.5"
                >
                  <MessageSquare className="w-3.5 h-3.5" />
                  <span>Note</span>
                </button>

                {/* Copy Button */}
                <button
                  onClick={handleCopyQuote}
                  className="px-2.5 py-1.5 rounded-lg hover:bg-bg text-text text-xs font-medium flex items-center gap-1.5"
                >
                  {copiedFeedback ? (
                    <Check className="w-3.5 h-3.5 text-green-500" />
                  ) : (
                    <Copy className="w-3.5 h-3.5" />
                  )}
                  <span>{copiedFeedback ? "Copied" : "Copy"}</span>
                </button>
              </div>
            ) : (
              <div className="flex flex-col gap-2 p-1 min-w-[240px]">
                <textarea
                  value={noteText}
                  onChange={(e) => setNoteText(e.target.value)}
                  placeholder="Add your thoughts or note..."
                  rows={2}
                  className="w-full text-xs p-2 rounded-lg border border-border bg-bg text-text focus:outline-none focus:border-accent resize-none"
                  autoFocus
                />
                <div className="flex items-center justify-end gap-2">
                  <button
                    onClick={() => setNoteInputOpen(false)}
                    className="px-2.5 py-1 text-xs rounded border border-border text-muted hover:text-text"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={() => handleSaveHighlight("yellow")}
                    className="px-3 py-1 text-xs rounded bg-accent text-white font-medium hover:opacity-90"
                  >
                    Save Note
                  </button>
                </div>
              </div>
            )}
          </div>
        )}
      </main>

      {/* Bottom auto-hiding progress footer */}
      <footer
        className={`fixed bottom-0 left-0 right-0 h-12 bg-surface/95 backdrop-blur-sm border-t border-border z-40 px-4 flex items-center justify-between transition-transform duration-200 ${
          controlsVisible ? "translate-y-0" : "translate-y-full"
        }`}
      >
        <button
          onClick={(e) => {
            e.stopPropagation();
            engineRef.current?.prev();
          }}
          className="p-1.5 rounded hover:bg-bg text-muted hover:text-text"
        >
          <ChevronLeft className="w-5 h-5" />
        </button>

        <div className="flex items-center gap-3 text-xs font-mono text-muted">
          <span>{currentPercent}%</span>
          <div className="w-32 bg-border h-1.5 rounded-full overflow-hidden hidden sm:block">
            <div
              className="bg-accent h-full transition-all duration-300"
              style={{ width: `${currentPercent}%` }}
            />
          </div>
        </div>

        <button
          onClick={(e) => {
            e.stopPropagation();
            engineRef.current?.next();
          }}
          className="p-1.5 rounded hover:bg-bg text-muted hover:text-text"
        >
          <ChevronRight className="w-5 h-5" />
        </button>
      </footer>

      {/* Table of Contents Sheet / Drawer */}
      {tocOpen && (
        <div className="fixed inset-0 z-50 flex">
          <div
            className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
            onClick={() => setTocOpen(false)}
          />
          <aside className="relative w-80 max-w-[85vw] h-full bg-surface border-r border-border p-4 flex flex-col z-10">
            <div className="flex items-center justify-between pb-3 border-b border-border mb-3">
              <h4 className="font-semibold text-text text-sm">Table of Contents</h4>
              <button
                onClick={() => setTocOpen(false)}
                className="text-xs px-2 py-1 rounded bg-bg text-muted hover:text-text"
              >
                Close
              </button>
            </div>
            <div className="flex-1 overflow-y-auto space-y-1">
              {tocItems.length === 0 ? (
                <p className="text-xs text-muted">No chapters found</p>
              ) : (
                tocItems.map((item, idx) => (
                  <button
                    key={idx}
                    onClick={() => {
                      engineRef.current?.goTo(item.location);
                      setTocOpen(false);
                    }}
                    className="w-full text-left px-3 py-2 rounded-lg hover:bg-bg text-xs font-medium text-text transition-colors flex items-center justify-between"
                  >
                    <span className="truncate">{item.title}</span>
                    <Bookmark className="w-3 h-3 text-muted/50 ml-2 flex-shrink-0" />
                  </button>
                ))
              )}
            </div>
          </aside>
        </div>
      )}

      {/* Typography & Settings Sheet */}
      {typographyOpen && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div
            className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
            onClick={() => setTypographyOpen(false)}
          />
          <aside className="relative w-80 max-w-[85vw] h-full bg-surface border-l border-border p-5 flex flex-col z-10 overflow-y-auto space-y-6">
            <div className="flex items-center justify-between pb-3 border-b border-border">
              <h4 className="font-semibold text-text text-sm">Appearance</h4>
              <button
                onClick={() => setTypographyOpen(false)}
                className="text-xs px-2 py-1 rounded bg-bg text-muted hover:text-text"
              >
                Done
              </button>
            </div>

            {/* Themes */}
            <div>
              <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
                Theme
              </span>
              <div className="grid grid-cols-3 gap-2">
                {(
                  [
                    { id: "light", label: "Light", icon: Sun },
                    { id: "warm", label: "Warm", icon: Coffee },
                    { id: "dark", label: "Dark", icon: Moon }
                  ] as const
                ).map(({ id, label, icon: Icon }) => (
                  <button
                    key={id}
                    onClick={() => setPrefs({ theme: id })}
                    className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg border text-xs font-medium ${
                      prefs.theme === id
                        ? "border-accent bg-accent/10 text-accent font-semibold"
                        : "border-border bg-bg text-text"
                    }`}
                  >
                    <Icon className="w-3.5 h-3.5" />
                    <span>{label}</span>
                  </button>
                ))}
              </div>
            </div>

            {/* Font Family */}
            <div>
              <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
                Typeface
              </span>
              <div className="grid grid-cols-2 gap-2">
                <button
                  onClick={() => setPrefs({ fontFamily: "serif" })}
                  className={`py-2 px-3 rounded-lg border text-xs font-serif ${
                    prefs.fontFamily === "serif"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  Serif (Literata)
                </button>
                <button
                  onClick={() => setPrefs({ fontFamily: "sans" })}
                  className={`py-2 px-3 rounded-lg border text-xs font-sans ${
                    prefs.fontFamily === "sans"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  Sans-Serif
                </button>
              </div>
            </div>

            {/* Font Size */}
            <div>
              <div className="flex items-center justify-between text-xs font-semibold uppercase tracking-wider text-muted mb-2">
                <span>Font Size</span>
                <span className="font-mono">{prefs.fontSize}px</span>
              </div>
              <div className="flex items-center gap-3">
                <button
                  onClick={() => setPrefs({ fontSize: Math.max(12, prefs.fontSize - 1) })}
                  className="flex-1 py-1.5 border border-border rounded-lg bg-bg text-sm font-bold"
                >
                  A-
                </button>
                <button
                  onClick={() => setPrefs({ fontSize: Math.min(36, prefs.fontSize + 1) })}
                  className="flex-1 py-1.5 border border-border rounded-lg bg-bg text-sm font-bold"
                >
                  A+
                </button>
              </div>
            </div>

            {/* Line Height */}
            <div>
              <div className="flex items-center justify-between text-xs font-semibold uppercase tracking-wider text-muted mb-2">
                <span>Line Spacing</span>
                <span className="font-mono">{prefs.lineHeight}</span>
              </div>
              <div className="flex gap-2">
                {[1.4, 1.6, 1.8, 2.0].map((lh) => (
                  <button
                    key={lh}
                    onClick={() => setPrefs({ lineHeight: lh })}
                    className={`flex-1 py-1 rounded-lg border text-xs font-mono ${
                      prefs.lineHeight === lh
                        ? "border-accent bg-accent/10 text-accent font-bold"
                        : "border-border bg-bg text-text"
                    }`}
                  >
                    {lh}
                  </button>
                ))}
              </div>
            </div>

            {/* Flow Mode */}
            <div>
              <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
                Reading Flow
              </span>
              <div className="grid grid-cols-2 gap-2">
                <button
                  onClick={() => setPrefs({ flow: "paginated" })}
                  className={`py-2 px-3 rounded-lg border text-xs ${
                    prefs.flow === "paginated"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  Paginated
                </button>
                <button
                  onClick={() => setPrefs({ flow: "scrolled" })}
                  className={`py-2 px-3 rounded-lg border text-xs ${
                    prefs.flow === "scrolled"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  Continuous
                </button>
              </div>
            </div>
          </aside>
        </div>
      )}
    </div>
  );
};
