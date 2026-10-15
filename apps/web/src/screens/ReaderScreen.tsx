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
  Loader2,
  Trash2
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

  // Parse location and query parameters (Phase 7: ?q= consumption)
  const queryParams = useMemo(() => new URLSearchParams(location.search), [location.search]);
  const targetLoc = queryParams.get("loc");
  const targetChapter = queryParams.get("chapter");
  const targetQuery = queryParams.get("q");

  const [doc, setDoc] = useState<DocMeta | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [controlsVisible, setControlsVisible] = useState(true);
  const [tocOpen, setTocOpen] = useState(false);
  const [typographyOpen, setTypographyOpen] = useState(false);
  const [documentSheetOpen, setDocumentSheetOpen] = useState(false);
  const [searchOpen, setSearchOpen] = useState(Boolean(targetQuery));
  const [tocItems, setTocItems] = useState<{ title: string; location: string; depth: number; percent?: number }[]>([]);
  const [currentPercent, setCurrentPercent] = useState<number>(0);
  const [currentCfi, setCurrentCfi] = useState<string>("");
  const [currentChapterTitle, setCurrentChapterTitle] = useState<string>("");
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [currentChapterHref, setCurrentChapterHref] = useState<string>("");
  const [annotations, setAnnotations] = useState<Annotation[]>([]);
  const [activeHighlight, setActiveHighlight] = useState<{
    annotation: Annotation;
    rect: DOMRect;
  } | null>(null);
  const [isScrolledNearBottom, setIsScrolledNearBottom] = useState(false);
  const activeTocItemRef = useRef<HTMLButtonElement | null>(null);

  // Synchronize bookmark state accurately with current location (Phase 6)
  const activeBookmark = useMemo(() => {
    if (!currentCfi && !currentChapterHref) return null;
    return (
      annotations.find(
        (a) =>
          a.kind === "bookmark" &&
          (a.location === currentCfi || (doc?.sourceFormat === "pdf" && a.location === currentChapterHref))
      ) || null
    );
  }, [annotations, currentCfi, currentChapterHref, doc?.sourceFormat]);

  const isBookmarked = !!activeBookmark;

  // Jump History & Return Pill
  const [jumpHistory, setJumpHistory] = useState<JumpHistoryItem | null>(null);

  // Scrubber State
  const [isScrubbing, setIsScrubbing] = useState(false);
  const [scrubPercent, setScrubPercent] = useState<number>(0);

  // In-Book Search State (pre-filled if opening with ?q=)
  const [searchQuery, setSearchQuery] = useState(targetQuery || "");
  const [searchResults, setSearchResults] = useState<{ location: string; excerpt: string }[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [activeSearchIndex, setActiveSearchIndex] = useState<number>(-1);

  // Sync searchQuery when URL query param changes dynamically
  useEffect(() => {
    if (targetQuery && targetQuery !== searchQuery) {
      setSearchQuery(targetQuery);
      setSearchOpen(true);
    }
  }, [targetQuery]);

  // Selection & Highlight Toolbar State
  const [selection, setSelection] = useState<SelectionData | null>(null);
  const [noteInputOpen, setNoteInputOpen] = useState(false);
  const [noteText, setNoteText] = useState("");
  const [noteColor, setNoteColor] = useState<"yellow" | "green" | "blue" | "pink">("yellow");
  const [copiedFeedback, setCopiedFeedback] = useState(false);
  const [tocActiveTab, setTocActiveTab] = useState<"contents" | "bookmarks" | "highlights">("contents");

  useEffect(() => {
    if (tocOpen && tocActiveTab === "contents" && activeTocItemRef.current) {
      activeTocItemRef.current.scrollIntoView({ block: "nearest", behavior: "smooth" });
    }
  }, [tocOpen, tocActiveTab]);

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

  // Keep screen awake using Screen Wake Lock API and re-acquire on visibility return
  useEffect(() => {
    let wakeLock: { release: () => Promise<void> } | null = null;
    const acquireLock = async () => {
      if (
        "wakeLock" in navigator &&
        (navigator as unknown as { wakeLock: { request: (type: string) => Promise<{ release: () => Promise<void> }> } }).wakeLock
      ) {
        try {
          wakeLock = await (
            navigator as unknown as { wakeLock: { request: (type: string) => Promise<{ release: () => Promise<void> }> } }
          ).wakeLock.request("screen");
        } catch {}
      }
    };
    void acquireLock();

    const handleVisibility = () => {
      if (document.visibilityState === "visible") {
        void acquireLock();
      }
    };
    document.addEventListener("visibilitychange", handleVisibility);

    return () => {
      document.removeEventListener("visibilitychange", handleVisibility);
      if (wakeLock) {
        wakeLock.release().catch(() => {});
      }
    };
  }, []);

  // Sync fullscreen state with standard fullscreenchange event
  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(!!document.fullscreenElement);
    };
    document.addEventListener("fullscreenchange", handleFullscreenChange);
    return () => document.removeEventListener("fullscreenchange", handleFullscreenChange);
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

  // Auto-hide chrome timer for mobile view (R-02: does not restart on page turns)
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
    typographyOpen,
    tocOpen,
    searchOpen,
    documentSheetOpen,
    selection,
    noteInputOpen,
    isScrubbing,
    isDesktop
  ]);

  // Android Back Gesture & Modal History Integration (Phase 5)
  const openModalName = useMemo(() => {
    if (noteInputOpen) return "note";
    if (searchOpen) return "search";
    if (tocOpen) return "toc";
    if (typographyOpen) return "typography";
    if (documentSheetOpen) return "meta";
    if (activeHighlight) return "highlight";
    return null;
  }, [noteInputOpen, searchOpen, tocOpen, typographyOpen, documentSheetOpen, activeHighlight]);

  const modalHistoryPushedRef = useRef<string | null>(null);
  const closingViaPopstateRef = useRef(false);

  useEffect(() => {
    if (openModalName) {
      if (!modalHistoryPushedRef.current) {
        modalHistoryPushedRef.current = openModalName;
        window.history.pushState({ folioModal: openModalName }, "");
      }
    } else {
      if (modalHistoryPushedRef.current && !closingViaPopstateRef.current) {
        modalHistoryPushedRef.current = null;
        if (window.history.state?.folioModal) {
          window.history.back();
        }
      }
      closingViaPopstateRef.current = false;
    }
  }, [openModalName]);

  useEffect(() => {
    const handlePopState = () => {
      if (modalHistoryPushedRef.current) {
        closingViaPopstateRef.current = true;
        modalHistoryPushedRef.current = null;
        setNoteInputOpen(false);
        setSearchOpen(false);
        setTocOpen(false);
        setTypographyOpen(false);
        setDocumentSheetOpen(false);
        setActiveHighlight(null);
      }
    };
    window.addEventListener("popstate", handlePopState);
    return () => window.removeEventListener("popstate", handlePopState);
  }, []);

  // Track currently opened document ID to avoid tearing down engine on doc metadata edits (R-11)
  const openedDocIdRef = useRef<string | null>(null);
  const docRef = useRef(doc);
  useEffect(() => {
    docRef.current = doc;
  }, [doc]);

  // Separate effect for navigating to targetLoc from URL without re-opening book
  const lastTargetLocRef = useRef<string | null>(null);
  useEffect(() => {
    if (targetLoc && targetLoc !== lastTargetLocRef.current && engineRef.current && openedDocIdRef.current === doc?.id) {
      lastTargetLocRef.current = targetLoc;
      engineRef.current.goTo(targetLoc).catch(console.error);
    }
  }, [targetLoc, doc?.id]);

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
    openedDocIdRef.current = doc.id;

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
        if (r.chapterId) {
          setCurrentChapterHref(r.chapterId);
        }
        setIsScrolledNearBottom(false);
        // Auto-dismiss floating selection toolbar on page turns
        setSelection(null);
        setActiveHighlight(null);
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

      // Load existing annotations into state and reader rendition
      const existingAnn = await libraryManager.getAnnotations(doc.id);
      if (cancelled) return;
      setAnnotations(existingAnn);
      for (const ann of existingAnn) {
        if (ann.kind !== "bookmark") {
          engine.addHighlight(ann);
        }
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
      openedDocIdRef.current = null;
      engine.destroy();
    };
  }, [doc?.id, doc?.sourceFormat]);

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
    };

    const handleHighlightClick = (e: Event) => {
      const custom = e as CustomEvent<{ annotationId: string; rect: DOMRect }>;
      const ann = annotations.find((a) => a.id === custom.detail.annotationId);
      if (ann) {
        setActiveHighlight({
          annotation: ann,
          rect: custom.detail.rect
        });
        setSelection(null);
      }
    };

    const handleScrollProgress = (e: Event) => {
      const custom = e as CustomEvent<{ isNearBottom: boolean }>;
      if (custom.detail) {
        setIsScrolledNearBottom(custom.detail.isNearBottom);
      }
    };

    const handleLinkJump = () => {
      recordJump();
    };

    el.addEventListener("folio:middle-tap", handleMiddleTap);
    el.addEventListener("folio:clear-selection", handleClearSelection);
    el.addEventListener("folio:touch-start", handleTouchStart);
    el.addEventListener("folio:touch-end", handleTouchEnd);
    el.addEventListener("folio:highlight-click", handleHighlightClick as EventListener);
    el.addEventListener("folio:scroll-progress", handleScrollProgress as EventListener);
    el.addEventListener("folio:link-jump", handleLinkJump);

    return () => {
      el.removeEventListener("folio:middle-tap", handleMiddleTap);
      el.removeEventListener("folio:clear-selection", handleClearSelection);
      el.removeEventListener("folio:touch-start", handleTouchStart);
      el.removeEventListener("folio:touch-end", handleTouchEnd);
      el.removeEventListener("folio:highlight-click", handleHighlightClick as EventListener);
      el.removeEventListener("folio:scroll-progress", handleScrollProgress as EventListener);
      el.removeEventListener("folio:link-jump", handleLinkJump);
    };
  }, [doc?.id, isDesktop, annotations]);

  // Apply typography updates dynamically
  useEffect(() => {
    if (engineRef.current) {
      engineRef.current.applyPrefs(prefs);
    }
  }, [prefs]);

  // Keyboard navigation & Shortcuts (/ for search, t for TOC, Esc to close, Shift+Space for prev)
  useEffect(() => {
    const handleKeyDown = (e: Event) => {
      const customEvent = e as CustomEvent<{
        key: string;
        code: string;
        shiftKey: boolean;
        altKey: boolean;
        ctrlKey: boolean;
        metaKey: boolean;
      }>;
      const isCustom = "detail" in e && typeof customEvent.detail === "object" && customEvent.detail !== null;
      const key = isCustom ? customEvent.detail.key : (e as KeyboardEvent).key;
      const shiftKey = isCustom ? customEvent.detail.shiftKey : (e as KeyboardEvent).shiftKey;
      const ctrlKey = isCustom ? customEvent.detail.ctrlKey : (e as KeyboardEvent).ctrlKey;
      const metaKey = isCustom ? customEvent.detail.metaKey : (e as KeyboardEvent).metaKey;

      if (ctrlKey || metaKey) return;
      if (noteInputOpen) return;

      const targetTag = (e.target as HTMLElement)?.tagName;
      if (targetTag === "INPUT" || targetTag === "TEXTAREA") {
        if (key === "Escape") {
          setSearchOpen(false);
          setTocOpen(false);
          setTypographyOpen(false);
          setDocumentSheetOpen(false);
        }
        return;
      }

      if (key === "/" && !searchOpen) {
        if (!isCustom) (e as KeyboardEvent).preventDefault();
        setSearchOpen(true);
      } else if (
        key === "ArrowRight" ||
        key === "PageDown" ||
        (key === " " && !shiftKey) ||
        (key === "ArrowDown" && prefs.flow === "paginated")
      ) {
        if (!isCustom && key === " ") (e as KeyboardEvent).preventDefault();
        engineRef.current?.next();
      } else if (
        key === "ArrowLeft" ||
        key === "PageUp" ||
        (key === " " && shiftKey) ||
        (key === "ArrowUp" && prefs.flow === "paginated")
      ) {
        if (!isCustom && key === " ") (e as KeyboardEvent).preventDefault();
        engineRef.current?.prev();
      } else if (key === "ArrowDown" && prefs.flow !== "paginated") {
        if (!isCustom) (e as KeyboardEvent).preventDefault();
        const scroller =
          (containerRef.current?.querySelector(".epub-container") as HTMLElement | null) ||
          containerRef.current;
        if (scroller) scroller.scrollBy({ top: 120, behavior: "smooth" });
      } else if (key === "ArrowUp" && prefs.flow !== "paginated") {
        if (!isCustom) (e as KeyboardEvent).preventDefault();
        const scroller =
          (containerRef.current?.querySelector(".epub-container") as HTMLElement | null) ||
          containerRef.current;
        if (scroller) scroller.scrollBy({ top: -120, behavior: "smooth" });
      } else if (key === "Home") {
        engineRef.current?.goToPercent?.(0);
      } else if (key === "End") {
        engineRef.current?.goToPercent?.(100);
      } else if (key === "t" || key === "T") {
        setTocOpen((prev) => !prev);
      } else if (key === "Escape") {
        setSelection(null);
        setTocOpen(false);
        setSearchOpen(false);
        setTypographyOpen(false);
        setDocumentSheetOpen(false);
        setJumpHistory(null);
      }
    };

    window.addEventListener("keydown", handleKeyDown as EventListener);
    const container = containerRef.current;
    if (container) {
      container.addEventListener("folio:keydown", handleKeyDown as EventListener);
    }

    return () => {
      window.removeEventListener("keydown", handleKeyDown as EventListener);
      if (container) {
        container.removeEventListener("folio:keydown", handleKeyDown as EventListener);
      }
    };
  }, [noteInputOpen, searchOpen, prefs.flow]);

  // Desktop wheel navigation on main canvas outside iframe
  useEffect(() => {
    const mainEl = containerRef.current?.parentElement;
    if (!mainEl) return;
    let lastWheelTurn = 0;
    const handleWheel = (e: WheelEvent) => {
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

      if (prefs.flow === "paginated") {
        const now = Date.now();
        if (now - lastWheelTurn > 350) {
          if (dy > 40 || dx > 40) {
            lastWheelTurn = now;
            engineRef.current?.next();
          } else if (dy < -40 || dx < -40) {
            lastWheelTurn = now;
            engineRef.current?.prev();
          }
        }
      } else {
        const scroller =
          (containerRef.current?.querySelector(".epub-container") as HTMLElement | null) ||
          containerRef.current;
        if (scroller) {
          scroller.scrollTop += dy;
          scroller.scrollLeft += dx;
        }
      }
    };
    mainEl.addEventListener("wheel", handleWheel, { passive: true });
    return () => mainEl.removeEventListener("wheel", handleWheel);
  }, [prefs.flow]);

  // Live In-Book Search with Progressive Section Streaming (Phase 7)
  useEffect(() => {
    if (!searchOpen || !searchQuery.trim()) {
      setSearchResults([]);
      setIsSearching(false);
      setActiveSearchIndex(-1);
      engineRef.current?.clearSearchHighlight?.();
      return;
    }

    let cancelled = false;
    setIsSearching(true);

    const timer = setTimeout(async () => {
      const engine = engineRef.current;
      if (!engine) return;
      const results: { location: string; excerpt: string }[] = [];
      let lastThrottle = Date.now();
      try {
        for await (const match of engine.search(searchQuery)) {
          if (cancelled) break;
          results.push(match);
          const now = Date.now();
          if (results.length <= 4 || now - lastThrottle > 120) {
            lastThrottle = now;
            setSearchResults([...results]);
          }
          if (results.length >= 80) break;
        }
        if (!cancelled) {
          setSearchResults([...results]);
          setIsSearching(false);
          setActiveSearchIndex((prev) => (prev === -1 && results.length > 0 ? 0 : prev));
        }
      } catch (err) {
        console.warn("Reader search error:", err);
        if (!cancelled) setIsSearching(false);
      }
    }, 250);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [searchOpen, searchQuery]);

  const handleJumpToSearchMatch = useCallback(
    (res: { location: string; excerpt: string }, idx: number) => {
      recordJump();
      setActiveSearchIndex(idx);
      engineRef.current?.goTo(res.location);
      engineRef.current?.highlightSearchMatch?.(res.location);
      if (!isDesktop) setSearchOpen(false);
    },
    [isDesktop, recordJump]
  );

  const handleNextSearchMatch = useCallback(() => {
    if (searchResults.length === 0) return;
    const nextIdx = (activeSearchIndex + 1) % searchResults.length;
    const match = searchResults[nextIdx];
    if (match) {
      handleJumpToSearchMatch(match, nextIdx);
    }
  }, [activeSearchIndex, searchResults, handleJumpToSearchMatch]);

  const handlePrevSearchMatch = useCallback(() => {
    if (searchResults.length === 0) return;
    const prevIdx = (activeSearchIndex - 1 + searchResults.length) % searchResults.length;
    const match = searchResults[prevIdx];
    if (match) {
      handleJumpToSearchMatch(match, prevIdx);
    }
  }, [activeSearchIndex, searchResults, handleJumpToSearchMatch]);

  // Chapter ticks along reading track (Top-level chapters only, Phase 4)
  const chapterTicks = useMemo(() => {
    return tocItems
      .filter((item) => (item.depth || 0) === 0)
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

  // Next chapter for scrolled flow continuity (Phase 3)
  const nextChapter = useMemo(() => {
    if (!currentChapterHref || tocItems.length === 0) return null;
    const cleanCurrent = (currentChapterHref || "").split("#")[0] ?? "";
    const currentIndex = tocItems.findIndex((item) => {
      const cleanLoc = (item.location || "").split("#")[0] ?? "";
      return Boolean(
        cleanLoc &&
        cleanCurrent &&
        (cleanLoc === cleanCurrent ||
          cleanLoc.endsWith(cleanCurrent) ||
          cleanCurrent.endsWith(cleanLoc))
      );
    });
    if (currentIndex >= 0 && currentIndex < tocItems.length - 1) {
      return tocItems[currentIndex + 1];
    }
    return null;
  }, [currentChapterHref, tocItems]);

  const isCommittingScrubRef = useRef(false);
  const handleCommitScrub = async (val: number) => {
    if (isCommittingScrubRef.current) return;
    isCommittingScrubRef.current = true;
    setTimeout(() => {
      isCommittingScrubRef.current = false;
    }, 200);

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

    if (doc?.sourceFormat === "pdf") {
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
      return;
    }

    // For EPUB in paginated mode: clicking the empty canvas gutters outside the book container navigates
    if (prefs.flow === "paginated" && containerRef.current) {
      const box = containerRef.current.getBoundingClientRect();
      if (e.clientX < box.left) {
        engineRef.current?.prev();
      } else if (e.clientX > box.right) {
        engineRef.current?.next();
      }
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
      chapterId: currentChapterHref || selection.chapterId,
      kind: noteText ? "note" : "highlight",
      color,
      note: noteText.trim() || undefined,
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString()
    };

    await libraryManager.addAnnotation(annotation);
    engineRef.current?.addHighlight(annotation);
    setAnnotations((prev) => [...prev, annotation]);

    // Clear native selection range in window and iframe
    if (typeof window !== "undefined" && window.getSelection) {
      window.getSelection()?.removeAllRanges();
    }
    const iframe = containerRef.current?.querySelector("iframe");
    if (iframe?.contentWindow?.getSelection) {
      iframe.contentWindow.getSelection()?.removeAllRanges();
    }

    setSelection(null);
    setNoteInputOpen(false);
    setNoteText("");
  };

  const handleDeleteHighlight = async (id: string) => {
    engineRef.current?.removeHighlight(id);
    await libraryManager.deleteAnnotation(id);
    setAnnotations((prev) => prev.filter((a) => a.id !== id));
    setActiveHighlight(null);
  };

  const handleChangeHighlightColor = async (id: string, color: "yellow" | "green" | "blue" | "pink") => {
    const found = annotations.find((a) => a.id === id);
    if (!found) return;
    const updated: Annotation = { ...found, color, updatedAt: new Date().toISOString() };
    await libraryManager.deleteAnnotation(id);
    await libraryManager.addAnnotation(updated);
    engineRef.current?.removeHighlight(id);
    engineRef.current?.addHighlight(updated);
    setAnnotations((prev) => prev.map((a) => (a.id === id ? updated : a)));
    setActiveHighlight((prev) => (prev ? { ...prev, annotation: updated } : null));
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
    if ("vibrate" in navigator) {
      try {
        navigator.vibrate(10);
      } catch {}
    }

    if (activeBookmark) {
      try {
        await libraryManager.deleteAnnotation(activeBookmark.id);
        setAnnotations((prev) => prev.filter((a) => a.id !== activeBookmark.id));
      } catch (err) {
        console.error("Failed to delete bookmark:", err);
      }
    } else {
      const newBookmark: Annotation = {
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
        await libraryManager.addAnnotation(newBookmark);
        setAnnotations((prev) => [...prev, newBookmark]);
      } catch (err) {
        console.error("Failed to add bookmark:", err);
      }
    }
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
          paddingLeft: "max(1rem, var(--sal, 0px))",
          paddingRight: "max(1rem, var(--sar, 0px))"
        }}
      >
        <div className="flex items-center gap-3 min-w-0">
          <button
            onClick={() => navigate("/")}
            title="Back to Library"
            className="min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg transition-colors text-muted hover:text-text shrink-0 flex items-center justify-center"
          >
            <ArrowLeft className="w-5 h-5" />
          </button>
          <button
            onClick={() => setDocumentSheetOpen(true)}
            className="text-left overflow-hidden max-w-xs sm:max-w-md hover:opacity-80 transition-opacity min-h-[44px] flex flex-col justify-center"
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
            className="min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors flex items-center justify-center"
          >
            <Search className="w-5 h-5" />
          </button>

          {/* Table of Contents */}
          <button
            onClick={() => setTocOpen(true)}
            title="Table of Contents"
            className="min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors flex items-center justify-center"
          >
            <List className="w-5 h-5" />
          </button>

          {/* Typography & Appearance */}
          <button
            onClick={() => setTypographyOpen(true)}
            title="Typography & Appearance"
            className="min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors flex items-center justify-center"
          >
            <Type className="w-5 h-5" />
          </button>

          {/* Fullscreen Toggle */}
          <button
            onClick={toggleFullscreen}
            title="Toggle Fullscreen"
            className="hidden sm:flex min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors items-center justify-center"
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
          paddingLeft: prefs.flow === "paginated" ? `${Math.max(12, Math.min(prefs.margin, 64))}px` : (isDesktop ? "16px" : "0px"),
          paddingRight: prefs.flow === "paginated" ? `${Math.max(12, Math.min(prefs.margin, 64))}px` : (isDesktop ? "16px" : "0px")
        }}
        onClick={handleContainerClick}
      >
        {/* Desktop Side Navigation Arrows (Paginated Flow) */}
        {isDesktop && prefs.flow === "paginated" && (
          <>
            <button
              onClick={(e) => {
                e.stopPropagation();
                engineRef.current?.prev();
              }}
              title="Previous Page (ArrowLeft / PageUp)"
              aria-label="Previous Page"
              className="absolute left-3 top-1/2 -translate-y-1/2 z-20 w-10 h-10 rounded-full bg-surface/85 hover:bg-surface border border-border/80 shadow-md flex items-center justify-center text-muted hover:text-text opacity-40 hover:opacity-100 focus:opacity-100 transition-all duration-150"
            >
              <ChevronLeft className="w-5 h-5" />
            </button>
            <button
              onClick={(e) => {
                e.stopPropagation();
                engineRef.current?.next();
              }}
              title="Next Page (ArrowRight / PageDown / Space)"
              aria-label="Next Page"
              className="absolute right-3 top-1/2 -translate-y-1/2 z-20 w-10 h-10 rounded-full bg-surface/85 hover:bg-surface border border-border/80 shadow-md flex items-center justify-center text-muted hover:text-text opacity-40 hover:opacity-100 focus:opacity-100 transition-all duration-150"
            >
              <ChevronRight className="w-5 h-5" />
            </button>
          </>
        )}

        {/* Book Container with Reading Width Cap and Brightness Filter */}
        <div
          ref={containerRef}
          className="h-full w-full mx-auto relative overflow-hidden"
          style={{
            maxWidth:
              prefs.flow === "scrolled"
                ? "100%"
                : prefs.spread === "auto" && isDesktop
                ? `${Math.max(840, Math.min(1280, (prefs.maxWidth ?? 720) * 1.75))}px`
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
              <div className="flex flex-col gap-2 p-1 min-w-[260px]">
                <textarea
                  value={noteText}
                  onChange={(e) => setNoteText(e.target.value)}
                  placeholder="Add your thoughts or note..."
                  rows={2}
                  className="w-full text-xs p-2 rounded-lg border border-border bg-bg text-text focus:outline-none focus:border-accent resize-none"
                  autoFocus
                />
                <div className="flex items-center justify-between gap-2 pt-1 border-t border-border/60">
                  <div className="flex items-center gap-1.5">
                    {(["yellow", "green", "blue", "pink"] as const).map((c) => {
                      const bgMap = {
                        yellow: "bg-amber-400 border-amber-500/40",
                        green: "bg-emerald-400 border-emerald-500/40",
                        blue: "bg-sky-400 border-sky-500/40",
                        pink: "bg-pink-400 border-pink-500/40"
                      };
                      return (
                        <button
                          key={c}
                          type="button"
                          onClick={() => setNoteColor(c)}
                          className={`w-5 h-5 rounded-full border transition-transform ${bgMap[c]} ${
                            noteColor === c ? "scale-125 ring-2 ring-accent" : "hover:scale-110"
                          }`}
                          title={`Color ${c}`}
                        />
                      );
                    })}
                  </div>
                  <div className="flex items-center gap-1.5">
                    <button
                      onClick={() => setNoteInputOpen(false)}
                      className="px-2.5 py-1 text-xs rounded border border-border text-muted hover:text-text min-h-[32px]"
                    >
                      Cancel
                    </button>
                    <button
                      onClick={() => handleSaveHighlight(noteColor)}
                      className="px-3 py-1 text-xs rounded bg-accent text-white font-medium hover:opacity-90 min-h-[32px]"
                    >
                      Save Note
                    </button>
                  </div>
                </div>
              </div>
            )}
          </div>
        )}

        {/* Active Highlight Popover (Phase 6) */}
        {activeHighlight && (
          <div
            onClick={(e) => e.stopPropagation()}
            className="fixed z-50 transform -translate-x-1/2 bg-surface border border-border rounded-xl shadow-2xl p-2.5 flex flex-col gap-2 transition-all animate-in fade-in zoom-in-95 max-w-xs text-xs"
            style={{
              left: `${Math.min(Math.max(activeHighlight.rect.left, 120), window.innerWidth - 120)}px`,
              top: `${activeHighlight.rect.top > 120 ? activeHighlight.rect.top - 80 : activeHighlight.rect.top + 24}px`
            }}
          >
            <div className="flex items-center justify-between gap-3 border-b border-border pb-1.5">
              <span className="font-semibold text-text uppercase text-[10px] tracking-wider">
                {activeHighlight.annotation.kind === "note" ? "Note" : "Highlight"}
              </span>
              <div className="flex items-center gap-1.5">
                {(["yellow", "green", "blue", "pink"] as const).map((color) => (
                  <button
                    key={color}
                    onClick={() => handleChangeHighlightColor(activeHighlight.annotation.id, color)}
                    className={`w-4 h-4 rounded-full transition-transform ${
                      activeHighlight.annotation.color === color ? "scale-125 ring-1 ring-accent" : "hover:scale-110"
                    }`}
                    style={{
                      backgroundColor:
                        color === "yellow" ? "var(--hl-yellow)" :
                        color === "green" ? "var(--hl-green)" :
                        color === "blue" ? "var(--hl-blue)" : "var(--hl-pink)"
                    }}
                    title={`Change to ${color}`}
                  />
                ))}
                <button
                  onClick={() => handleDeleteHighlight(activeHighlight.annotation.id)}
                  className="p-1 rounded text-red-500 hover:bg-red-500/10 ml-1 transition-colors"
                  title="Delete highlight"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                </button>
                <button
                  onClick={() => setActiveHighlight(null)}
                  className="p-1 rounded text-muted hover:text-text hover:bg-bg transition-colors"
                  title="Close"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              </div>
            </div>
            {activeHighlight.annotation.note && (
              <div className="p-1.5 rounded bg-bg text-text text-[11px] font-mono leading-tight">
                {activeHighlight.annotation.note}
              </div>
            )}
            <div className="italic text-muted line-clamp-2 text-[11px]">
              "{activeHighlight.annotation.quote}"
            </div>
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
          paddingLeft: "max(0.75rem, var(--sal, 0px))",
          paddingRight: "max(0.75rem, var(--sar, 0px))"
        }}
      >
        <button
          onClick={(e) => {
            e.stopPropagation();
            engineRef.current?.prev();
          }}
          className="hidden sm:flex min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors shrink-0 items-center justify-center"
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
            className="text-xs font-mono font-semibold text-muted hover:text-text transition-colors shrink-0 min-h-[36px] flex items-center"
          >
            {isScrubbing ? `${Math.round(scrubPercent)}%` : displayPercent}
          </button>

          {/* Scrubber Track with Chapter Ticks */}
          <div className="relative flex-1 flex items-center h-8 group">
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

            {/* Visible Scrubber Thumb (Phase 5 & 8) */}
            <div
              className={`absolute top-1/2 -translate-y-1/2 -translate-x-1/2 rounded-full bg-surface border-2 border-accent shadow-md transition-all pointer-events-none ${
                isScrubbing ? "w-4 h-4 scale-125 ring-2 ring-accent/30" : "w-3 h-3 group-hover:scale-110"
              }`}
              style={{
                left: `${Math.min(100, Math.max(0, isScrubbing ? scrubPercent : currentPercent))}%`
              }}
            />

            {/* Scrub Input Range */}
            <input
              type="range"
              min="0"
              max="100"
              step="0.1"
              value={isScrubbing ? scrubPercent : currentPercent}
              onPointerDown={() => {
                setIsScrubbing(true);
                setScrubPercent(currentPercent);
              }}
              onChange={(e) => {
                const val = Number(e.target.value);
                setScrubPercent(val);
              }}
              onPointerUp={(e) => {
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
              className="text-xs text-muted hover:text-text transition-colors truncate hidden sm:block max-w-[140px] md:max-w-[200px] min-h-[36px]"
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
            className={`min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg transition-colors flex items-center justify-center ${
              isBookmarked ? "text-accent" : "text-muted hover:text-text"
            }`}
          >
            <Bookmark className="w-5 h-5" />
          </button>
          <button
            onClick={(e) => {
              e.stopPropagation();
              engineRef.current?.next();
            }}
            className="hidden sm:flex min-w-[44px] min-h-[44px] p-2 rounded-lg hover:bg-bg text-muted hover:text-text transition-colors items-center justify-center"
            title="Next page"
            aria-label="Next page"
          >
            <ChevronRight className="w-5 h-5" />
          </button>
        </div>
      </footer>

      {/* Scrolled Flow Next Chapter Continuance Pill (Phase 3) */}
      {prefs.flow !== "paginated" && isScrolledNearBottom && nextChapter && (
        <div className="fixed bottom-14 left-1/2 -translate-x-1/2 z-30 animate-in fade-in slide-in-from-bottom-2 duration-200 pointer-events-auto">
          <button
            onClick={() => {
              recordJump();
              engineRef.current?.goTo(nextChapter.location);
              setIsScrolledNearBottom(false);
            }}
            className="flex items-center gap-2 px-4 py-2 rounded-full bg-surface/95 backdrop-blur-xs border border-accent/40 shadow-xl text-xs font-medium text-text hover:bg-accent/15 hover:border-accent transition-all group"
          >
            <span className="text-muted">Next:</span>
            <span className="font-semibold text-accent max-w-[180px] sm:max-w-[260px] truncate">{nextChapter.title}</span>
            <ChevronRight className="w-4 h-4 text-accent transition-transform group-hover:translate-x-0.5" />
          </button>
        </div>
      )}

      {/* In-Book Search Drawer (Non-modal on desktop) */}
      {searchOpen && (
        <div className={`fixed inset-0 z-50 flex justify-end ${isDesktop ? "pointer-events-none" : ""}`}>
          {!isDesktop && (
            <div
              className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
              onClick={() => {
                setSearchOpen(false);
                engineRef.current?.clearSearchHighlight?.();
              }}
            />
          )}
          <aside className="pointer-events-auto relative w-96 max-w-[90vw] h-full bg-surface/98 backdrop-blur-md border-l border-border p-4 flex flex-col z-10 shadow-2xl animate-in slide-in-from-right duration-200">
            <div className="flex items-center justify-between pb-3 border-b border-border mb-3">
              <div className="flex items-center gap-2">
                <Search className="w-4 h-4 text-accent" />
                <h4 className="font-semibold text-text text-sm">In-Book Search</h4>
              </div>
              <button
                onClick={() => {
                  setSearchOpen(false);
                  engineRef.current?.clearSearchHighlight?.();
                }}
                className="text-xs px-2.5 py-1.5 min-h-[32px] rounded-md bg-bg text-muted hover:text-text border border-border"
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
                className="w-full text-xs py-2 pl-8 pr-8 rounded-lg border border-border bg-bg text-text focus:outline-none focus:border-accent min-h-[38px]"
              />
              <Search className="w-3.5 h-3.5 text-muted absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
              {searchQuery && (
                <button
                  onClick={() => setSearchQuery("")}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-muted hover:text-text p-1"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>

            {/* Search Results Summary & Stepper */}
            <div className="text-[11px] text-muted mb-2 flex items-center justify-between min-h-6">
              <span>
                {isSearching ? (
                  <span className="flex items-center gap-1.5">
                    <Loader2 className="w-3 h-3 animate-spin text-accent" />
                    <span>Searching... ({searchResults.length} found)</span>
                  </span>
                ) : searchQuery ? (
                  `Found ${searchResults.length} match${searchResults.length === 1 ? "" : "es"}`
                ) : (
                  "Type to search within this book"
                )}
              </span>
              {searchResults.length > 0 && (
                <div className="flex items-center gap-1 shrink-0">
                  <span className="font-mono text-[10px] text-muted mr-1">
                    {activeSearchIndex >= 0 ? `${activeSearchIndex + 1}/${searchResults.length}` : ""}
                  </span>
                  <button
                    onClick={handlePrevSearchMatch}
                    title="Previous match"
                    className="p-1.5 rounded hover:bg-bg text-muted hover:text-text border border-border min-h-[32px] min-w-[32px] flex items-center justify-center"
                  >
                    <ChevronLeft className="w-3.5 h-3.5" />
                  </button>
                  <button
                    onClick={handleNextSearchMatch}
                    title="Next match"
                    className="p-1.5 rounded hover:bg-bg text-muted hover:text-text border border-border min-h-[32px] min-w-[32px] flex items-center justify-center"
                  >
                    <ChevronRight className="w-3.5 h-3.5" />
                  </button>
                </div>
              )}
            </div>

            {/* Search Results List */}
            <div className="flex-1 overflow-y-auto space-y-2">
              {searchResults.length > 0 ? (
                searchResults.map((res, idx) => {
                  const isActive = activeSearchIndex === idx;
                  return (
                    <button
                      key={idx}
                      onClick={() => handleJumpToSearchMatch(res, idx)}
                      className={`w-full text-left p-2.5 rounded-lg border transition-all text-xs ${
                        isActive
                          ? "border-accent bg-accent/10 shadow-xs ring-1 ring-accent/30"
                          : "border-border bg-bg hover:border-accent/60"
                      }`}
                    >
                      <div className="flex items-center justify-between text-muted text-[10px] uppercase font-mono mb-1">
                        <span>Match {idx + 1}</span>
                        {isActive && <span className="text-accent font-semibold">Active</span>}
                      </div>
                      <div className="text-text leading-relaxed line-clamp-3">
                        {highlightSnippet(res.excerpt, searchQuery)}
                      </div>
                    </button>
                  );
                })
              ) : searchQuery && !isSearching ? (
                <div className="text-xs text-muted p-4 text-center">
                  No matching passages found.
                </div>
              ) : null}
            </div>
          </aside>
        </div>
      )}

      {/* Table of Contents Sheet / Drawer with Contents, Bookmarks, Highlights Tabs */}
      {tocOpen && (
        <div className={`fixed inset-0 z-50 flex ${isDesktop ? "pointer-events-none" : ""}`}>
          {!isDesktop && (
            <div
              className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
              onClick={() => setTocOpen(false)}
            />
          )}
          <aside className="pointer-events-auto relative w-80 sm:w-96 max-w-[85vw] h-full bg-surface/98 backdrop-blur-md border-r border-border p-4 flex flex-col z-10 shadow-2xl animate-in slide-in-from-left duration-200">
            <div className="flex items-center justify-between pb-3 border-b border-border mb-3">
              <h4 className="font-semibold text-text text-sm">Table of Contents</h4>
              <button
                onClick={() => setTocOpen(false)}
                className="text-xs px-2.5 py-1.5 min-h-[32px] rounded-md bg-bg text-muted hover:text-text border border-border"
              >
                Close
              </button>
            </div>

            {/* Navigation Tabs */}
            <div className="grid grid-cols-3 p-1 rounded-xl bg-bg border border-border text-xs font-semibold mb-3">
              <button
                onClick={() => setTocActiveTab("contents")}
                className={`py-1.5 min-h-[36px] rounded-lg transition-all flex items-center justify-center ${
                  tocActiveTab === "contents"
                    ? "bg-surface text-accent shadow-xs"
                    : "text-muted hover:text-text"
                }`}
              >
                Contents
              </button>
              <button
                onClick={() => setTocActiveTab("bookmarks")}
                className={`py-1.5 min-h-[36px] rounded-lg transition-all flex items-center justify-center ${
                  tocActiveTab === "bookmarks"
                    ? "bg-surface text-accent shadow-xs"
                    : "text-muted hover:text-text"
                }`}
              >
                Bookmarks
              </button>
              <button
                onClick={() => setTocActiveTab("highlights")}
                className={`py-1.5 min-h-[36px] rounded-lg transition-all flex items-center justify-center ${
                  tocActiveTab === "highlights"
                    ? "bg-surface text-accent shadow-xs"
                    : "text-muted hover:text-text"
                }`}
              >
                Notes
              </button>
            </div>

            <div className="flex-1 overflow-y-auto space-y-1">
              {tocActiveTab === "contents" && (
                tocItems.length === 0 ? (
                  <p className="text-xs text-muted p-2">No chapters found</p>
                ) : (
                  tocItems.map((item, idx) => {
                    const cleanItemHref = (item.location || "").split("#")[0] ?? "";
                    const cleanCurrentHref = (currentChapterHref || "").split("#")[0] ?? "";
                    const isCurrent = Boolean(
                      (cleanCurrentHref && (cleanItemHref === cleanCurrentHref || cleanItemHref.endsWith(cleanCurrentHref) || cleanCurrentHref.endsWith(cleanItemHref))) ||
                      (item.title && currentChapterTitle && item.title.trim().toLowerCase() === currentChapterTitle.trim().toLowerCase())
                    );
                    return (
                      <button
                        key={idx}
                        ref={isCurrent ? activeTocItemRef : undefined}
                        aria-current={isCurrent ? "true" : undefined}
                        onClick={() => {
                          recordJump();
                          engineRef.current?.goTo(item.location);
                          if (!isDesktop) setTocOpen(false);
                        }}
                        style={{ paddingLeft: `${12 + (item.depth || 0) * 14}px` }}
                        className={`w-full text-left py-2.5 pr-3 rounded-lg text-xs font-medium transition-colors flex items-center justify-between min-h-[40px] ${
                          isCurrent
                            ? "bg-accent/15 text-accent font-semibold ring-1 ring-accent/30"
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
                )
              )}

              {tocActiveTab === "bookmarks" && (
                annotations.filter((a) => a.kind === "bookmark").length === 0 ? (
                  <p className="text-xs text-muted p-4 text-center">No bookmarks saved yet. Use the bookmark icon in the footer.</p>
                ) : (
                  annotations
                    .filter((a) => a.kind === "bookmark")
                    .map((bm) => (
                      <div
                        key={bm.id}
                        className="flex items-center justify-between p-2 rounded-lg border border-border bg-bg hover:border-accent/60 transition-all text-xs"
                      >
                        <button
                          onClick={() => {
                            if (bm.location) {
                              recordJump();
                              engineRef.current?.goTo(bm.location);
                              if (!isDesktop) setTocOpen(false);
                            }
                          }}
                          className="flex-1 text-left truncate mr-2 min-h-[36px] flex flex-col justify-center"
                        >
                          <div className="font-medium text-text truncate">{bm.quote || "Bookmark"}</div>
                          <div className="text-[10px] text-muted font-mono">{new Date(bm.createdAt).toLocaleDateString()}</div>
                        </button>
                        <button
                          onClick={async () => {
                            await libraryManager.deleteAnnotation(bm.id);
                            setAnnotations((prev) => prev.filter((a) => a.id !== bm.id));
                          }}
                          title="Delete bookmark"
                          className="p-1.5 rounded text-muted hover:text-red-500 hover:bg-surface transition-colors min-h-[36px] min-w-[36px] flex items-center justify-center"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    ))
                )
              )}

              {tocActiveTab === "highlights" && (
                annotations.filter((a) => a.kind !== "bookmark").length === 0 ? (
                  <p className="text-xs text-muted p-4 text-center">No highlights or notes saved yet. Select text to highlight.</p>
                ) : (
                  annotations
                    .filter((a) => a.kind !== "bookmark")
                    .map((ann) => (
                      <div
                        key={ann.id}
                        className="p-2.5 rounded-lg border border-border bg-bg hover:border-accent/60 transition-all text-xs space-y-1.5"
                      >
                        <div className="flex items-center justify-between">
                          <div className="flex items-center gap-1.5">
                            <span
                              className={`w-2 h-2 rounded-full ${
                                ann.color === "green"
                                  ? "bg-emerald-400"
                                  : ann.color === "blue"
                                  ? "bg-sky-400"
                                  : ann.color === "pink"
                                  ? "bg-pink-400"
                                  : "bg-amber-400"
                              }`}
                            />
                            <span className="font-mono text-[10px] uppercase text-muted">
                              {ann.kind === "note" ? "Note" : "Highlight"}
                            </span>
                          </div>
                          <button
                            onClick={() => handleDeleteHighlight(ann.id)}
                            title="Delete"
                            className="p-1.5 rounded text-muted hover:text-red-500 hover:bg-surface transition-colors min-h-[32px] min-w-[32px] flex items-center justify-center"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                        <button
                          onClick={() => {
                            if (ann.location) {
                              recordJump();
                              engineRef.current?.goTo(ann.location);
                              if (!isDesktop) setTocOpen(false);
                            }
                          }}
                          className="text-left w-full"
                        >
                          <p className="line-clamp-2 italic text-muted text-[11px] border-l-2 border-border pl-2 my-1">
                            "{ann.quote}"
                          </p>
                          {ann.note && (
                            <p className="text-text font-medium text-xs mt-1">
                              {ann.note}
                            </p>
                          )}
                        </button>
                      </div>
                    ))
                )
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
