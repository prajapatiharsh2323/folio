import React, { useEffect, useRef, useState, useCallback } from "react";
import { useParams, useNavigate } from "react-router-dom";
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
  Bookmark
} from "lucide-react";
import { EpubEngine } from "../reader/epub-engine.js";
import { useLibraryStore, useReaderStore, fileStore, libraryManager } from "../state/library-store.js";
import type { DocMeta } from "@folio/core";

export const ReaderScreen: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();

  const [doc, setDoc] = useState<DocMeta | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [tocOpen, setTocOpen] = useState(false);
  const [typographyOpen, setTypographyOpen] = useState(false);
  const [tocItems, setTocItems] = useState<{ title: string; location: string; depth: number }[]>([]);
  const [currentPercent, setCurrentPercent] = useState<number>(0);
  const [currentCfi, setCurrentCfi] = useState<string>("");
  const [isFullscreen, setIsFullscreen] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const engineRef = useRef<EpubEngine | null>(null);

  const { prefs, setPrefs } = useReaderStore();
  const libraryDocs = useLibraryStore((state) => state.docs);

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
    if ("wakeLock" in navigator && (navigator as unknown as { wakeLock: { request: (type: string) => Promise<{ release: () => Promise<void> }> } }).wakeLock) {
      (navigator as unknown as { wakeLock: { request: (type: string) => Promise<{ release: () => Promise<void> }> } }).wakeLock
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
    (cfi: string, percent: number) => {
      if (!doc) return;
      const progress = {
        docId: doc.id,
        location: cfi,
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

  // Initialize EpubEngine when doc is ready
  useEffect(() => {
    if (!doc || !containerRef.current) return;

    const engine = new EpubEngine();
    engineRef.current = engine;

    async function init() {
      if (!doc || !containerRef.current) return;
      // Load saved progress location
      const saved = await libraryManager.getProgress(doc.id);
      const readingFile = `library/docs/${doc.slug}/${doc.files.reading || "reading/book.epub"}`;

      let epubBytes: Uint8Array | undefined;
      let url: string | undefined;

      if (await fileStore.exists(readingFile)) {
        epubBytes = await fileStore.read(readingFile);
      } else {
        // Fallback for direct test fixtures
        url = `/fixtures/${doc.files.original || "english-novel.epub"}`;
      }

      await engine.open(doc, containerRef.current, {
        location: saved?.location,
        prefs,
        epubBytes,
        url
      });

      engine.onRelocate((r) => {
        setCurrentCfi(r.location);
        setCurrentPercent(r.percent);
      });

      const toc = await engine.getToc();
      setTocItems(toc);
    }

    init();

    return () => {
      engine.destroy();
    };
  }, [doc]);

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
      if (e.key === "ArrowRight" || e.key === "PageDown" || e.key === " ") {
        engineRef.current?.next();
      } else if (e.key === "ArrowLeft" || e.key === "PageUp") {
        engineRef.current?.prev();
      } else if (e.key === "t" || e.key === "T") {
        setTocOpen((prev) => !prev);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);

  const handleContainerClick = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const width = rect.width;

    if (x < width / 3) {
      // Tap left third: previous page
      engineRef.current?.prev();
    } else if (x > (2 * width) / 3) {
      // Tap right third: next page
      engineRef.current?.next();
    } else {
      // Tap middle third: toggle top/bottom interface chrome
      setControlsVisible((prev) => !prev);
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
            <p className="text-[11px] text-muted truncate">{doc.author || "Folio Reader"}</p>
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
      <main className="flex-1 relative h-full w-full overflow-hidden" onClick={handleContainerClick}>
        <div ref={containerRef} className="h-full w-full" />
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
