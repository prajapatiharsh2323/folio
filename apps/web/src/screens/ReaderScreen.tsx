import React, { useEffect, useRef, useState, useCallback, useMemo } from "react";
import { useParams, useNavigate, useLocation } from "react-router-dom";
import {
  ArrowLeft,
  Type,
  List,
  Search,
  ChevronLeft,
  ChevronRight,
  Maximize2,
  Minimize2,
  Bookmark,
  Copy,
  MessageSquare,
  Check,
  RotateCcw,
  Info,
  X,
  Loader2
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
import { TypographySheet } from "../components/TypographySheet.js";
import { DocumentSheet } from "../components/DocumentSheet.js";
import type { DocMeta, Annotation } from "@folio/core";

interface SelectionData {
  quote: string;
  prefix: string;
  suffix: string;
  location: string;
  rect: DOMRect;
  chapterId?: string;
}

interface JumpHistoryItem {
  location: string;
  percent: number;
  label: string;
}

function highlightSnippet(text: string, query: string) {
  if (!query.trim()) return text;
  const escaped = query.trim().replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const parts = text.split(new RegExp(`(${escaped})`, "gi"));
  return parts.map((part, i) =>
    part.toLowerCase() === query.trim().toLowerCase() ? (
      <mark key={i} className="bg-accent/25 text-accent font-semibold rounded px-0.5">
        {part}
      </mark>
    ) : (
      part
    )
  );
}

export const ReaderScreen: React.FC = () => {
  const { slug } = useParams<{ slug: string }>();
  const navigate = useNavigate();
  const location = useLocation();

  const [doc, setDoc] = useState<DocMeta | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [tocOpen, setTocOpen] = useState(false);
  const [typographyOpen, setTypographyOpen] = useState(false);
  const [documentSheetOpen, setDocumentSheetOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(false);
  const [tocItems, setTocItems] = useState<{ title: string; location: string; depth: number; percent?: number }[]>([]);
  const [currentPercent, setCurrentPercent] = useState<number>(0);
  const [currentCfi, setCurrentCfi] = useState<string>("");
  const [currentChapterTitle, setCurrentChapterTitle] = useState<string>("");
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [isBookmarked, setIsBookmarked] = useState(false);

  // Jump History & Return Pill
  const [jumpHistory, setJumpHistory] = useState<JumpHistoryItem | null>(null);

  // Scrubber State
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [scrubPercent, setScrubPercent] = useState<number>(0);

  // In-Book Search State
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<{ location: string; excerpt: string }[]>([]);
  const [isSearching, setIsSearching] = useState(false);

  // Selection & Highlight Toolbar State
  const [selection, setSelection] = useState<SelectionData | null>(null);
  const [noteInputOpen, setNoteInputOpen] = useState(false);
  const [noteText, setNoteText] = useState("");
  const [copiedFeedback, setCopiedFeedback] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const engineRef = useRef<ReaderEngine | null>(null);
  const touchDownRef = useRef(false);

  const { prefs } = useReaderStore();
  const libraryDocs = useLibraryStore((state) => state.docs);

  const [isDesktop, setIsDesktop] = useState(
    typeof window !== "undefined" ? window.innerWidth >= 768 : false
  );

  useEffect(() => {
    const handleResize = () => setIsDesktop(window.innerWidth >= 768);
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  // Parse location and query parameters
  const queryParams = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const targetLoc = queryParams.get("loc");
  const targetChapter = queryParams.get("chapter");

  // Load document metadata and saved progress
  useEffect(() => {
    async function load() {
      let found = libraryDocs.find((d) => d.slug === slug);
      if (!found) {
        const metaPath = `library/docs/${slug}/meta.json`;
        if (await fileStore.exists(metaPath)) {
          try {
            found = JSON.parse(await fileStore.readText(metaPath)) as DocMeta;
          } catch {
            setError("Document metadata is corrupted.");
            return;
          }
        }
      }
      if (found) {
        setDoc(found);
      } else {
        setError("Document not found in library.");
      }
    }
    load();
  }, [slug, libraryDocs]);

  // Keep screen awake using Screen Wake Lock API
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

  const recordJump = useCallback(() => {
    if (currentCfi) {
      setJumpHistory({
        location: currentCfi,
        percent: currentPercent,
        label: currentChapterTitle || `${Math.round(currentPercent)}%`
      });
    }
  }, [currentCfi, currentPercent, currentChapterTitle]);

  // Debounced progress saving (1.5s after change)
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

  // Auto-hide chrome timer for mobile view
  useEffect(() => {
    if (isDesktop) {
      setControlsVisible(true);
      return;
    }
    if (!controlsVisible) return;
    // Freeze timer while sheet, search, modal, or text selection is active
    if (
      typographyOpen ||
      tocOpen ||
      searchOpen ||
      documentSheetOpen ||
      selection !== null ||
      noteInputOpen ||
      isScrubbing
    ) {
      return;
    }
    if (touchDownRef.current) return;

    const timer = setTimeout(() => {
      setControlsVisible(false);
    }, 2800);

    return () => clearTimeout(timer);
  }, [
    controlsVisible,
    currentCfi,
    currentPercent,
    typographyOpen,
    tocOpen,
    searchOpen,
    documentSheetOpen,
    selection,
    noteInputOpen,
    isScrubbing,
    isDesktop
  ]);

  // Initialize Engine (EpubEngine or PdfEngine)
  useEffect(() => {
    if (!doc || !containerRef.current) return;

    let cancelled = false;
    let engine: ReaderEngine;
    if (doc.sourceFormat === "pdf") {
      engine = new PdfEngine();
    } else {
      engine = new EpubEngine();
    }
    engineRef.current = engine;

    async function init() {
      if (!doc || !containerRef.current || cancelled) return;
      const saved = await libraryManager.getProgress(doc.id);
      if (cancelled) return;

      // Determine initial location: targetLoc -> targetChapter -> saved -> first page
      let initialLocation = targetLoc || undefined;
      if (!initialLocation && targetChapter) {
        const foundCh = doc.chapters.find((c) => c.id === targetChapter);
        if (foundCh) initialLocation = foundCh.href;
      }
      if (!initialLocation) {
        initialLocation = saved?.location;
      }
      if (
        !initialLocation ||
        typeof initialLocation !== "string" ||
        initialLocation.trim() === "" ||
        initialLocation.includes("clean/index.html")
      ) {
        initialLocation = undefined;
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
        throw new Error("Document content not found in storage.");
      }

      if (cancelled || !containerRef.current) return;

      // Register relocation and selection listeners BEFORE opening
      engine.onRelocate((r) => {
        if (cancelled) return;
        setCurrentCfi(r.location);
        setCurrentPercent(r.percent);
        if (r.chapterTitle) {
          setCurrentChapterTitle(r.chapterTitle);
        }
      });

      engine.onSelection((s) => {
        if (cancelled) return;
        setSelection(s);
        setNoteInputOpen(false);
        setNoteText("");
      });

      // Wait for layout if container clientHeight is 0 during initial render pass
      if (containerRef.current.clientHeight === 0) {
        await new Promise<void>((resolve) => {
          const el = containerRef.current;
          if (!el || el.clientHeight > 0) {
            resolve();
            return;
          }
          const ro = new ResizeObserver((entries) => {
            for (const entry of entries) {
              if (entry.contentRect.height > 0) {
                ro.disconnect();
                resolve();
              }
            }
          });
          ro.observe(el);
          setTimeout(() => {
            ro.disconnect();
            resolve();
          }, 300);
        });
      }
      if (cancelled || !containerRef.current) return;

      await engine.open(doc, containerRef.current, {
        location: initialLocation,
        prefs,
        epubBytes,
        url
      });

      if (cancelled) return;

      // Load existing annotations
      const existingAnn = await libraryManager.getAnnotations(doc.id);
      if (cancelled) return;
      for (const ann of existingAnn) {
        engine.addHighlight(ann);
      }

      const toc = await engine.getToc();
      if (cancelled) return;
      setTocItems(toc);
    }

    init().catch((err) => {
      if (!cancelled) {
        console.error("Reader failed to open document:", err);
        setError((err as Error).message || "Failed to open document.");
      }
    });

    return () => {
      cancelled = true;
      engine.destroy();
    };
  }, [doc, targetLoc, targetChapter]);

  const latestProgressRef = useRef({
    docId: doc?.id,
    location: currentCfi,
    percent: currentPercent
  });
  useEffect(() => {
    latestProgressRef.current = {
      docId: doc?.id,
      location: currentCfi,
      percent: currentPercent
    };
  }, [doc, currentCfi, currentPercent]);

  // Flush reading progress immediately on visibility change, pagehide, or unmount
  useEffect(() => {
    const flushProgress = () => {
      const p = latestProgressRef.current;
      if (p.docId && p.location) {
        libraryManager
          .saveProgress({
            docId: p.docId,
            location: p.location,
            percent: p.percent,
            updatedAt: new Date().toISOString()
          })
          .catch(console.error);
      }
    };

    const handleVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        flushProgress();
      }
    };

    window.addEventListener("pagehide", flushProgress);
    document.addEventListener("visibilitychange", handleVisibilityChange);

    return () => {
      window.removeEventListener("pagehide", flushProgress);
      document.removeEventListener("visibilitychange", handleVisibilityChange);
      flushProgress();
    };
  }, []);

  // Events dispatched from iframe content hook
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const handleMiddleTap = () => {
      setControlsVisible((prev) => !prev);
    };
    const handleClearSelection = () => {
      setSelection(null);
    };
    const handleTouchStart = () => {
      touchDownRef.current = true;
    };
    const handleTouchEnd = () => {
      touchDownRef.current = false;
      if (!isDesktop) {
        setControlsVisible(true);
      }
    };

    el.addEventListener("folio:middle-tap", handleMiddleTap);
    el.addEventListener("folio:clear-selection", handleClearSelection);
    el.addEventListener("folio:touch-start", handleTouchStart);
    el.addEventListener("folio:touch-end", handleTouchEnd);

    return () => {
      el.removeEventListener("folio:middle-tap", handleMiddleTap);
      el.removeEventListener("folio:clear-selection", handleClearSelection);
      el.removeEventListener("folio:touch-start", handleTouchStart);
      el.removeEventListener("folio:touch-end", handleTouchEnd);
    };
  }, [doc, isDesktop]);

  // Apply typography updates dynamically
  useEffect(() => {
    if (engineRef.current) {
      engineRef.current.applyPrefs(prefs);
    }
  }, [prefs]);

  // Keyboard navigation & Shortcuts (/ for search, t for TOC, Esc to close)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (noteInputOpen) return;
      const targetTag = (e.target as HTMLElement)?.tagName;
      if (targetTag === "INPUT" || targetTag === "TEXTAREA") {
        if (e.key === "Escape") {
          setSearchOpen(false);
          setTocOpen(false);
          setTypographyOpen(false);
          setDocumentSheetOpen(false);
        }
        return;
      }

      if (e.key === "/" && !searchOpen) {
        e.preventDefault();
        setSearchOpen(true);
      } else if (e.key === "ArrowRight" || e.key === "PageDown" || e.key === " ") {
        engineRef.current?.next();
      } else if (e.key === "ArrowLeft" || e.key === "PageUp") {
        engineRef.current?.prev();
      } else if (e.key === "t" || e.key === "T") {
        setTocOpen((prev) => !prev);
      } else if (e.key === "Escape") {
        setSelection(null);
        setTocOpen(false);
        setSearchOpen(false);
        setTypographyOpen(false);
        setDocumentSheetOpen(false);
        setJumpHistory(null);
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [noteInputOpen, searchOpen]);

  // Live In-Book Search
  useEffect(() => {
    if (!searchOpen || !searchQuery.trim()) {
      setSearchResults([]);
      setIsSearching(false);
      return;
    }

    let cancelled = false;
    setIsSearching(true);

    const timer = setTimeout(async () => {
      const engine = engineRef.current;
      if (!engine) return;
      const results: { location: string; excerpt: string }[] = [];
      try {
        for await (const match of engine.search(searchQuery)) {
          if (cancelled) break;
          results.push(match);
          if (results.length >= 80) break;
        }
        if (!cancelled) {
          setSearchResults(results);
          setIsSearching(false);
        }
      } catch (err) {
        console.warn("Reader search error:", err);
        if (!cancelled) setIsSearching(false);
      }
    }, 280);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [searchOpen, searchQuery]);

  // Chapter ticks along reading track
  const chapterTicks = useMemo(() => {
    return tocItems
      .map((item, idx) => ({
        title: item.title,
        location: item.location,
        percent: item.percent ?? Math.round((idx / Math.max(1, tocItems.length - 1)) * 100)
      }))
      .filter((t) => t.percent >= 0 && t.percent <= 100);
  }, [tocItems]);

  // Closest chapter during scrubbing
  const displayedPercent = isScrubbing ? scrubPercent : currentPercent;
  const closestChapter = useMemo(() => {
    if (chapterTicks.length === 0) return null;
    const first = chapterTicks[0]!;
    return chapterTicks.reduce((prev, curr) =>
      Math.abs(curr.percent - displayedPercent) < Math.abs(prev.percent - displayedPercent)
        ? curr
        : prev,
      first
    );
  }, [chapterTicks, displayedPercent]);

  const handleCommitScrub = async (val: number) => {
    setIsScrubbing(false);
    recordJump();
    const clamped = Math.max(0, Math.min(100, val));
    if (engineRef.current?.goToPercent) {
      await engineRef.current.goToPercent(clamped);
    } else {
      const first = chapterTicks[0];
      if (first) {
        const closest = chapterTicks.reduce((prev, curr) =>
          Math.abs(curr.percent - clamped) < Math.abs(prev.percent - clamped) ? curr : prev,
          first
        );
        if (closest) {
          await engineRef.current?.goTo(closest.location);
        }
      }
    }
  };

  // Container click handler restricted strictly to PDF
  const handleContainerClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (selection) {
      setSelection(null);
      return;
    }

    if (doc?.sourceFormat !== "pdf") {
      return;
    }

    const rect = e.currentTarget.getBoundingClientRect();
    const paddingLeft = prefs.margin || 20;
    const paddingRight = prefs.margin || 20;
    const left = rect.left + paddingLeft;
    const right = rect.right - paddingRight;
    const x = e.clientX;

    if (x < left + (right - left) / 3) {
      engineRef.current?.prev();
    } else if (x > right - (right - left) / 3) {
      engineRef.current?.next();
    } else {
      setControlsVisible((prev) => !prev);
    }
  };

  const handleSaveHighlight = async (color: "yellow" | "green" | "blue" | "pink") => {
    if (!doc || !selection) return;

    if ("vibrate" in navigator) {
      try {
        navigator.vibrate(10);
      } catch {}
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
    } catch {}
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

  const toggleBookmark = async () => {
    if (!doc) return;
    setIsBookmarked(!isBookmarked);
    const annotation: Annotation = {
      id: crypto.randomUUID(),
      docId: doc.id,
      quote: currentChapterTitle || doc.title,
      prefix: "",
      suffix: "",
      location: currentCfi,
      kind: "bookmark",
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };
    try {
      await libraryManager.addAnnotation(annotation);
    } catch {}
  };

  // Error Card
  if (error) {
    return (
      <div className="flex flex-col h-[100dvh] w-screen items-center justify-center bg-bg text-text p-6 text-center">
        <div className="max-w-md p-6 bg-surface border border-border rounded-2xl shadow-lg">
          <div className="w-12 h-12 bg-accent/10 text-accent rounded-full flex items-center justify-center mx-auto mb-4">
            <Info className="w-6 h-6" />
          </div>
          <h3 className="text-lg font-semibold text-text mb-2">Unable to open document</h3>
          <p className="text-sm text-muted mb-6 leading-relaxed">{error}</p>
          <div className="flex items-center justify-center gap-3">
            <button
              onClick={() => navigate("/")}
              className="px-4 py-2 border border-border bg-bg text-text rounded-xl text-sm font-medium hover:bg-border/30 transition-colors"
            >
              Back to Library
            </button>
            <button
              onClick={() => window.location.reload()}
              className="flex items-center gap-1.5 px-4 py-2 bg-accent text-white rounded-xl text-sm font-medium hover:opacity-90 transition-opacity"
            >
              <RotateCcw className="w-4 h-4" />
              <span>Retry</span>
            </button>
          </div>
        </div>
      </div>
    );
  }

  // Loading Skeleton
  if (!doc) {
    return (
      <div className="flex flex-col h-[100dvh] w-screen bg-bg text-text p-8 items-center justify-center">
        <div className="w-full max-w-lg space-y-5 animate-pulse">
          <div className="h-6 bg-border/80 rounded-md w-1/2 mx-auto" />
          <div className="space-y-3 pt-6">
            <div className="h-4 bg-border/50 rounded w-full" />
            <div className="h-4 bg-border/40 rounded w-5/6" />
            <div className="h-4 bg-border/50 rounded w-full" />
            <div className="h-4 bg-border/30 rounded w-4/5" />
            <div className="h-4 bg-border/40 rounded w-3/4" />
          </div>
        </div>
      </div>
    );
  }

  // Clamped Toolbar positioning
  const toolbarTop = selection
    ? selection.rect.top > 120
      ? selection.rect.top - 60
      : selection.rect.bottom + 12
    : 0;
  const toolbarLeft = selection
    ? Math.min(
        Math.max(selection.rect.left + selection.rect.width / 2, 140),
        window.innerWidth - 140
      )
    : 0;

  const displayPercent =
    currentPercent % 1 === 0 ? `${currentPercent}%` : `${currentPercent.toFixed(1)}%`;

  return (
    <div
      data-theme={prefs.theme}
      className="relative h-[100dvh] w-screen overflow-hidden select-none bg-bg text-text flex flex-col md:grid md:grid-rows-[auto_1fr_auto]"
    >
      {/* Top Header */}
      <header
        className={`fixed top-0 left-0 right-0 z-40 bg-surface/95 backdrop-blur-sm border-b border-border px-4 flex items-center justify-between transition-transform duration-200 motion-reduce:transition-none md:relative md:top-auto md:left-auto md:right-auto md:translate-y-0 ${
          controlsVisible ? "translate-y-0" : "-translate-y-full"
        }`}
        style={{
          height: "calc(3.5rem + var(--sat, 0px))",
          paddingTop: "var(--sat, 0px)",
          paddingLeft: "max(1rem, var(--sat, 0px))",
          paddingRight: "max(1rem, var(--sat, 0px))"
        }}
      >
        <div className="flex items-center gap-3 min-w-0">
          <button
            onClick={() => navigate("/")}
            title="Back to Library"
            className="p-2 rounded-lg hover:bg-bg transition-colors text-muted hover:text-text shrink-0"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <button
            onClick={() => setDocumentSheetOpen(true)}
            className="text-left overflow-hidden max-w-xs sm:max-w-md hover:opacity-80 transition-opacity"
            title="View Document Details"
          >
            <h3 className="font-semibold text-sm truncate text-text">{doc.title}</h3>
            <p className="text-[11px] text-muted truncate">
              {doc.author || "Folio Reader"} · {doc.sourceFormat.toUpperCase()}
            </p>
          </button>
        </div>

        <div className="flex items-center gap-1 shrink-0">
          {/* In-Book Search button */}
          <button
            onClick={() => setSearchOpen(true)}
            title="Search in book (/)"
            className="p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            <Search className="w-5 h-5" />
          </button>

          {/* Table of Contents */}
          <button
            onClick={() => setTocOpen(true)}
            title="Table of Contents"
            className="p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            <List className="w-5 h-5" />
          </button>

          {/* Typography & Appearance */}
          <button
            onClick={() => setTypographyOpen(true)}
            title="Typography & Appearance"
            className="p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            <Type className="w-5 h-5" />
          </button>

          {/* Fullscreen Toggle */}
          <button
            onClick={toggleFullscreen}
            title="Toggle Fullscreen"
            className="hidden sm:inline-flex p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
          >
            {isFullscreen ? <Minimize2 className="w-5 h-5" /> : <Maximize2 className="w-5 h-5" />}
          </button>
        </div>
      </header>

      {/* Main Reading Canvas: strictly bounded with min-h-0 min-w-0 to prevent any flex/grid overflow */}
      <main
        className="flex-1 min-h-0 min-w-0 relative w-full overflow-hidden flex flex-col items-center justify-center"
        style={{
          paddingTop: isDesktop ? "12px" : "calc(3.5rem + var(--sat, 0px))",
          paddingBottom: isDesktop ? "12px" : "calc(3.25rem + var(--sab, 0px))",
          paddingLeft: isDesktop ? `${prefs.margin}px` : "0px",
          paddingRight: isDesktop ? `${prefs.margin}px` : "0px"
        }}
        onClick={handleContainerClick}
      >
        {/* Book Container with Reading Width Cap and Brightness Filter */}
        <div
          ref={containerRef}
          className="h-full w-full mx-auto relative overflow-hidden"
          style={{
            maxWidth:
              prefs.spread === "auto" && isDesktop
                ? `${Math.min(1280, (prefs.maxWidth ?? 720) * 1.75)}px`
                : `${prefs.maxWidth ?? 720}px`,
            filter:
              prefs.brightness && prefs.brightness < 100
                ? `brightness(${prefs.brightness}%)`
                : undefined
          }}
        />

        {/* Text Selection Floating Toolbar */}
        {selection && (
          <div
            onClick={(e) => e.stopPropagation()}
            className="fixed z-50 transform -translate-x-1/2 bg-surface border border-border rounded-xl shadow-2xl p-2 flex flex-col gap-2 transition-all animate-in fade-in zoom-in-95"
            style={{
              left: `${toolbarLeft}px`,
              top: `${toolbarTop}px`
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

      {/* "Return to previous position" Pill */}
      {jumpHistory && (
        <div className="fixed bottom-16 sm:bottom-14 left-1/2 -translate-x-1/2 z-40 animate-in fade-in slide-in-from-bottom-2 duration-200">
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-full bg-surface border border-accent/40 shadow-xl text-xs font-medium text-text backdrop-blur-md">
            <button
              onClick={() => {
                const target = jumpHistory.location;
                setJumpHistory(null);
                engineRef.current?.goTo(target);
              }}
              className="flex items-center gap-1.5 text-accent hover:opacity-80 transition-opacity"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              <span>Return to {jumpHistory.label} ({Math.round(jumpHistory.percent)}%)</span>
            </button>
            <button
              onClick={() => setJumpHistory(null)}
              className="p-0.5 rounded-full hover:bg-border/60 text-muted hover:text-text transition-colors"
              aria-label="Dismiss return prompt"
            >
              <X className="w-3 h-3" />
            </button>
          </div>
        </div>
      )}

      {/* Bottom Footer / Status Bar with Interactive Scrubber */}
      <footer
        className={`fixed bottom-0 left-0 right-0 z-40 bg-surface/95 backdrop-blur-sm border-t border-border px-3 sm:px-4 flex items-center justify-between gap-2 transition-transform duration-200 motion-reduce:transition-none md:relative md:bottom-auto md:left-auto md:right-auto md:translate-y-0 ${
          controlsVisible ? "translate-y-0" : "translate-y-full"
        }`}
        style={{
          height: "calc(3.25rem + var(--sab, 0px))",
          paddingBottom: "var(--sab, 0px)",
          paddingLeft: "max(0.75rem, var(--sat, 0px))",
          paddingRight: "max(0.75rem, var(--sat, 0px))"
        }}
      >
        <button
          onClick={(e) => {
            e.stopPropagation();
            engineRef.current?.prev();
          }}
          className="p-1.5 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors shrink-0"
          title="Previous page"
          aria-label="Previous page"
        >
          <ChevronLeft className="w-5 h-5" />
        </button>

        {/* Center Interactive Scrubber */}
        <div className="flex-1 max-w-xl mx-2 flex items-center gap-2.5 min-w-0">
          <button
            onClick={() => setTocOpen(true)}
            title="Open Table of Contents"
            className="text-xs font-mono font-semibold text-muted hover:text-text transition-colors shrink-0"
          >
            {isScrubbing ? `${Math.round(scrubPercent)}%` : displayPercent}
          </button>

          {/* Scrubber Track with Chapter Ticks */}
          <div className="relative flex-1 flex items-center h-6 group">
            {/* Live Scrub Tooltip */}
            {isScrubbing && (
              <div
                className="absolute -top-7 -translate-x-1/2 bg-surface text-text border border-border text-[11px] font-medium px-2 py-0.5 rounded shadow-lg pointer-events-none whitespace-nowrap z-30"
                style={{ left: `${Math.max(8, Math.min(92, scrubPercent))}%` }}
              >
                <span>{Math.round(scrubPercent)}%</span>
                {closestChapter?.title && (
                  <span className="text-muted ml-1 truncate max-w-[140px] inline-block align-bottom">
                    · {closestChapter.title}
                  </span>
                )}
              </div>
            )}

            {/* Base Background Track */}
            <div className="w-full bg-border h-1.5 rounded-full overflow-hidden relative">
              <div
                className="bg-accent h-full transition-[width] duration-75"
                style={{
                  width: `${Math.min(100, Math.max(0, isScrubbing ? scrubPercent : currentPercent))}%`
                }}
              />
            </div>

            {/* Chapter Ticks */}
            {chapterTicks.map((tick, i) => (
              <div
                key={i}
                className="absolute top-1/2 -translate-y-1/2 w-1 h-2 rounded-full bg-muted/40 hover:bg-accent transition-colors pointer-events-none"
                style={{ left: `${Math.max(0, Math.min(100, tick.percent))}%` }}
              />
            ))}

            {/* Scrub Input Range */}
            <input
              type="range"
              min="0"
              max="100"
              step="0.1"
              value={isScrubbing ? scrubPercent : currentPercent}
              onMouseDown={() => {
                setIsScrubbing(true);
                setScrubPercent(currentPercent);
              }}
              onTouchStart={() => {
                setIsScrubbing(true);
                setScrubPercent(currentPercent);
              }}
              onChange={(e) => {
                const val = Number(e.target.value);
                setScrubPercent(val);
              }}
              onMouseUp={(e) => {
                handleCommitScrub(Number((e.target as HTMLInputElement).value));
              }}
              onTouchEnd={(e) => {
                handleCommitScrub(Number((e.target as HTMLInputElement).value));
              }}
              className="absolute inset-0 w-full h-full opacity-0 cursor-pointer z-20"
              aria-label="Reading progress scrubber"
            />
          </div>

          {/* Current Chapter Title */}
          {currentChapterTitle && (
            <button
              onClick={() => setTocOpen(true)}
              className="text-xs text-muted hover:text-text transition-colors truncate hidden sm:block max-w-[140px] md:max-w-[200px]"
              title={`Chapter: ${currentChapterTitle}`}
            >
              {currentChapterTitle}
            </button>
          )}
        </div>

        <div className="flex items-center gap-1 shrink-0">
          <button
            onClick={toggleBookmark}
            title={isBookmarked ? "Remove Bookmark" : "Add Bookmark"}
            className={`p-1.5 rounded-lg hover:bg-bg transition-colors ${
              isBookmarked ? "text-accent" : "text-muted hover:text-text"
            }`}
          >
            <Bookmark className="w-4 h-4" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              engineRef.current?.next();
            }}
            className="p-1.5 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors"
            title="Next page"
            aria-label="Next page"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>
      </footer>

      {/* In-Book Search Drawer */}
      {searchOpen && (
        <div className="fixed inset-0 z-50 flex justify-end">
          <div
            className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
            onClick={() => setSearchOpen(false)}
          />
          <aside className="relative w-96 max-w-[90vw] h-full bg-surface border-l border-border p-4 flex flex-col z-10 shadow-2xl animate-in slide-in-from-right duration-200">
            <div className="flex items-center justify-between pb-3 border-b border-border mb-3">
              <div className="flex items-center gap-2">
                <Search className="w-4 h-4 text-accent" />
                <h4 className="font-semibold text-text text-sm">In-Book Search</h4>
              </div>
              <button
                onClick={() => setSearchOpen(false)}
                className="text-xs px-2.5 py-1 rounded-md bg-bg text-muted hover:text-text border border-border"
              >
                Close
              </button>
            </div>

            {/* Search Input Box */}
            <div className="relative mb-3">
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search text or phrases..."
                autoFocus
                className="w-full text-xs py-2 pl-8 pr-8 rounded-lg border border-border bg-bg text-text focus:outline-none focus:border-accent"
              />
              <Search className="w-3.5 h-3.5 text-muted absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
              {searchQuery && (
                <button
                  onClick={() => setSearchQuery("")}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-text"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>

            {/* Search Results Summary */}
            <div className="text-[11px] text-muted mb-2 flex items-center justify-between">
              <span>
                {isSearching ? (
                  <span className="flex items-center gap-1.5">
                    <Loader2 className="w-3 h-3 animate-spin text-accent" />
                    <span>Searching document...</span>
                  </span>
                ) : searchQuery ? (
                  `Found ${searchResults.length} match${searchResults.length === 1 ? "" : "es"}`
                ) : (
                  "Type to search within this book"
                )}
              </span>
            </div>

            {/* Search Results List */}
            <div className="flex-1 overflow-y-auto space-y-2">
              {searchResults.length > 0 ? (
                searchResults.map((res, idx) => (
                  <button
                    key={idx}
                    onClick={() => {
                      recordJump();
                      engineRef.current?.goTo(res.location);
                      if (!isDesktop) setSearchOpen(false);
                    }}
                    className="w-full text-left p-2.5 rounded-lg border border-border bg-bg hover:border-accent/60 transition-all text-xs"
                  >
                    <div className="text-muted text-[10px] uppercase font-mono mb-1">
                      Match {idx + 1}
                    </div>
                    <div className="text-text leading-relaxed line-clamp-3">
                      {highlightSnippet(res.excerpt, searchQuery)}
                    </div>
                  </button>
                ))
              ) : searchQuery && !isSearching ? (
                <div className="text-xs text-muted p-4 text-center">
                  No matching passages found.
                </div>
              ) : null}
            </div>
          </aside>
        </div>
      )}

      {/* Table of Contents Sheet / Drawer */}
      {tocOpen && (
        <div className="fixed inset-0 z-50 flex">
          <div
            className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
            onClick={() => setTocOpen(false)}
          />
          <aside className="relative w-80 max-w-[85vw] h-full bg-surface border-r border-border p-4 flex flex-col z-10 shadow-2xl animate-in slide-in-from-left duration-200">
            <div className="flex items-center justify-between pb-3 border-b border-border mb-3">
              <h4 className="font-semibold text-text text-sm">Table of Contents</h4>
              <button
                onClick={() => setTocOpen(false)}
                className="text-xs px-2.5 py-1 rounded-md bg-bg text-muted hover:text-text border border-border"
              >
                Close
              </button>
            </div>
            <div className="flex-1 overflow-y-auto space-y-1">
              {tocItems.length === 0 ? (
                <p className="text-xs text-muted p-2">No chapters found</p>
              ) : (
                tocItems.map((item, idx) => {
                  const isCurrent =
                    item.location === currentCfi ||
                    (item.title && currentChapterTitle.includes(item.title));
                  return (
                    <button
                      key={idx}
                      onClick={() => {
                        recordJump();
                        engineRef.current?.goTo(item.location);
                        setTocOpen(false);
                      }}
                      style={{ paddingLeft: `${12 + (item.depth || 0) * 14}px` }}
                      className={`w-full text-left py-2 pr-3 rounded-lg text-xs font-medium transition-colors flex items-center justify-between ${
                        isCurrent
                          ? "bg-accent/10 text-accent font-semibold"
                          : "hover:bg-bg text-text"
                      }`}
                    >
                      <span className="truncate">{item.title}</span>
                      {isCurrent && (
                        <span className="w-1.5 h-1.5 rounded-full bg-accent shrink-0 ml-2" />
                      )}
                    </button>
                  );
                })
              )}
            </div>
          </aside>
        </div>
      )}

      {/* Typography & Appearance Sheet */}
      <TypographySheet open={typographyOpen} onOpenChange={setTypographyOpen} />

      {/* Document Metadata Sheet */}
      <DocumentSheet
        doc={doc}
        open={documentSheetOpen}
        onOpenChange={setDocumentSheetOpen}
        onUpdated={(updated) => setDoc(updated)}
      />
    </div>
  );
};
