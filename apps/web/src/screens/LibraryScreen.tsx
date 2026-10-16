import React, { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Plus,
  BookOpen,
  Clock,
  BookMarked,
  FolderOpen,
  CheckCircle2,
  Trash2
} from "lucide-react";
import { useLibraryStore, libraryManager } from "../state/library-store.js";
import type { DocMeta, Collection, Progress } from "@folio/core";
import { DocumentSheet } from "../components/DocumentSheet.js";
import { BookCard } from "../components/BookCard.js";

export const LibraryScreen: React.FC = () => {
  const navigate = useNavigate();
  const {
    docs,
    recentDoc,
    loading,
    folderNeedsPermission,
    reconnectFolder,
    loadLibrary,
    sortBy,
    setSortBy,
    filterKind,
    setFilterKind
  } = useLibraryStore();

  const [collections, setCollections] = useState<Collection[]>([]);
  const [selectedCollection, setSelectedCollection] = useState<string>("all");
  const [selectedTag, setSelectedTag] = useState<string>("all");
  const [allProgress, setAllProgress] = useState<Record<string, Progress>>({});
  const [activeSheetDoc, setActiveSheetDoc] = useState<DocMeta | null>(null);
  const [selectedDocIds, setSelectedDocIds] = useState<Set<string>>(new Set());

  const [customHues, setCustomHues] = useState<Record<string, number>>(() => {
    try {
      return JSON.parse(localStorage.getItem("folio_custom_card_hues") || "{}");
    } catch {
      return {};
    }
  });

  const handleHueChange = (docId: string, hue: number) => {
    setCustomHues((prev) => {
      const updated = { ...prev, [docId]: hue };
      try {
        localStorage.setItem("folio_custom_card_hues", JSON.stringify(updated));
      } catch {
        /* ignore */
      }
      return updated;
    });
  };

  const handleSelectToggle = (docId: string) => {
    setSelectedDocIds((prev) => {
      const next = new Set(prev);
      if (next.has(docId)) {
        next.delete(docId);
      } else {
        next.add(docId);
      }
      return next;
    });
  };

  const handleToggleFinished = async (docId: string, markFinished: boolean) => {
    try {
      const p = {
        docId,
        location: markFinished ? "finished" : "",
        percent: markFinished ? 100 : 0,
        updatedAt: new Date().toISOString()
      };
      await libraryManager.saveProgress(p);
      setAllProgress((prev) => ({ ...prev, [docId]: p }));
    } catch (err) {
      console.error("Failed to update progress:", err);
    }
  };

  const handleDeleteDoc = async (docId: string) => {
    try {
      await libraryManager.deleteDocument(docId);
      setSelectedDocIds((prev) => {
        const next = new Set(prev);
        next.delete(docId);
        return next;
      });
      await loadLibrary();
    } catch (err) {
      console.error("Failed to delete document:", err);
    }
  };

  const handleBatchMarkFinished = async () => {
    for (const docId of Array.from(selectedDocIds)) {
      await handleToggleFinished(docId, true);
    }
    setSelectedDocIds(new Set());
  };

  const handleBatchDelete = async () => {
    if (
      window.confirm(
        `Are you sure you want to delete ${selectedDocIds.size} selected books from your library?`
      )
    ) {
      for (const docId of Array.from(selectedDocIds)) {
        await libraryManager.deleteDocument(docId);
      }
      setSelectedDocIds(new Set());
      await loadLibrary();
    }
  };

  useEffect(() => {
    loadLibrary();
    libraryManager.getCollections().then(setCollections).catch(console.error);
    libraryManager.getAllProgress().then(setAllProgress).catch(console.error);
  }, [loadLibrary]);

  // Extract all unique tags
  const allTags = Array.from(new Set(docs.flatMap((d) => d.tags || []))).sort();

  // Filter docs
  const filteredDocs = docs.filter((doc) => {
    if (filterKind !== "all" && doc.kind !== filterKind) return false;
    if (selectedCollection !== "all") {
      const coll = collections.find((c) => c.id === selectedCollection);
      if (!coll || !coll.docIds.includes(doc.id)) return false;
    }
    if (selectedTag !== "all" && !(doc.tags || []).includes(selectedTag)) return false;
    return true;
  });

  // Sort docs
  const sortedDocs = [...filteredDocs].sort((a, b) => {
    if (sortBy === "title") return a.title.localeCompare(b.title);
    if (sortBy === "author") return (a.author || "").localeCompare(b.author || "");
    if (sortBy === "progress") {
      const pA = allProgress[a.id]?.percent || 0;
      const pB = allProgress[b.id]?.percent || 0;
      return pB - pA;
    }
    return new Date(b.importedAt).getTime() - new Date(a.importedAt).getTime();
  });

  const handleOpenDoc = (doc: DocMeta) => {
    navigate(`/read/${doc.slug}`);
  };

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-5xl mx-auto w-full">
      {/* Header bar */}
      <header className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-6 border-b border-border mb-6">
        <div className="flex items-center gap-3">
          <div className="md:hidden w-10 h-10 rounded-xl bg-surface border border-border/60 p-1 flex items-center justify-center shadow-xs overflow-hidden shrink-0">
            <img src="/icons/logo.png" alt="Folio Logo" className="w-full h-full object-contain" />
          </div>
          <div>
            <h2 className="text-2xl font-bold tracking-tight text-text">Library</h2>
            <p className="text-sm text-muted">
              {docs.length} {docs.length === 1 ? "document" : "documents"} offline
            </p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <select
            value={sortBy}
            onChange={(e) =>
              setSortBy(e.target.value as "recent" | "title" | "author" | "progress")
            }
            aria-label="Sort library documents"
            className="px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:border-accent"
          >
            <option value="recent">Sort by Recent</option>
            <option value="title">Sort by Title</option>
            <option value="author">Sort by Author</option>
            <option value="progress">Sort by Progress</option>
          </select>
          <Link
            to="/import"
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-accent text-white text-sm font-medium hover:opacity-90 transition-opacity shadow-sm"
          >
            <Plus className="w-4 h-4" />
            <span>Import</span>
          </Link>
        </div>
      </header>

      {/* Folder mode permission prompt banner */}
      {folderNeedsPermission && (
        <div className="mb-6 p-4 rounded-xl border border-accent/40 bg-accent/10 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-text">
          <div className="flex items-center gap-3">
            <FolderOpen className="w-5 h-5 text-accent flex-shrink-0" />
            <div>
              <h4 className="text-sm font-semibold text-text">Reconnect Folder Library</h4>
              <p className="text-xs text-muted">
                Folio is configured to use a local folder library. Browser security requires a quick click to re-grant folder access after restart.
              </p>
            </div>
          </div>
          <button
            onClick={() => reconnectFolder()}
            className="px-3.5 py-1.5 bg-accent text-white font-medium text-xs rounded-lg hover:opacity-90 transition-opacity shrink-0 shadow-sm"
          >
            Grant Access
          </button>
        </div>
      )}

      {/* Filter Chips Bar */}
      <div className="flex flex-wrap items-center gap-2 mb-6 text-xs">
        {/* Kind Filters */}
        <div className="inline-flex rounded-lg border border-border p-0.5 bg-surface mr-2">
          <button
            onClick={() => setFilterKind("all")}
            className={`px-3 py-1 rounded-md font-medium transition-colors ${
              filterKind === "all" ? "bg-accent text-white" : "text-muted hover:text-text"
            }`}
          >
            All
          </button>
          <button
            onClick={() => setFilterKind("book")}
            className={`px-3 py-1 rounded-md font-medium transition-colors ${
              filterKind === "book" ? "bg-accent text-white" : "text-muted hover:text-text"
            }`}
          >
            Books
          </button>
          <button
            onClick={() => setFilterKind("article")}
            className={`px-3 py-1 rounded-md font-medium transition-colors ${
              filterKind === "article" ? "bg-accent text-white" : "text-muted hover:text-text"
            }`}
          >
            Articles
          </button>
        </div>

        {/* Collection Filter */}
        {collections.length > 0 && (
          <select
            value={selectedCollection}
            onChange={(e) => setSelectedCollection(e.target.value)}
            className="px-2.5 py-1.5 rounded-lg border border-border bg-surface text-text focus:outline-none focus:border-accent text-xs"
          >
            <option value="all">All Collections</option>
            {collections.map((c) => (
              <option key={c.id} value={c.id}>
                Collection: {c.name} ({c.docIds.length})
              </option>
            ))}
          </select>
        )}

        {/* Tag Filter */}
        {allTags.length > 0 && (
          <select
            value={selectedTag}
            onChange={(e) => setSelectedTag(e.target.value)}
            className="px-2.5 py-1.5 rounded-lg border border-border bg-surface text-text focus:outline-none focus:border-accent text-xs"
          >
            <option value="all">All Tags</option>
            {allTags.map((t) => (
              <option key={t} value={t}>
                #{t}
              </option>
            ))}
          </select>
        )}
      </div>

      {/* Continue reading row */}
      {recentDoc && filterKind === "all" && selectedCollection === "all" && (
        <section className="mb-8">
          <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-muted mb-3">
            <Clock className="w-3.5 h-3.5" />
            <span>Continue reading</span>
          </div>
          <div
            onClick={() => handleOpenDoc(recentDoc)}
            className="p-4 rounded-card border border-border bg-surface hover:border-accent/50 cursor-pointer transition-all flex items-center justify-between"
          >
            <div className="flex items-center gap-4">
              <div className="w-12 h-16 rounded-md bg-accent/15 text-accent flex items-center justify-center font-bold">
                <BookMarked className="w-6 h-6" />
              </div>
              <div>
                <h4 className="font-semibold text-text text-base">{recentDoc.title}</h4>
                <p className="text-xs text-muted mt-0.5">
                  {recentDoc.author || "Unknown author"} · {recentDoc.chapters.length} chapters
                  {allProgress[recentDoc.id] && ` · ${allProgress[recentDoc.id]?.percent}% read`}
                </p>
              </div>
            </div>
            <span className="text-xs font-semibold text-accent px-3 py-1.5 rounded-lg bg-accent/10">
              Resume
            </span>
          </div>
        </section>
      )}

      {/* Main library grid or skeleton cards */}
      {loading ? (
        <section className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4 sm:gap-5 animate-pulse">
          {[1, 2, 3, 4, 5, 6, 7, 8].map((i) => (
            <div
              key={i}
              className="aspect-[3/4.2] rounded-[18px] border border-border/60 bg-surface/40 p-4 flex flex-col justify-between"
            >
              <div className="flex justify-between items-center">
                <div className="w-6 h-6 rounded-[7px] bg-border/50" />
                <div className="w-10 h-4 rounded-full bg-border/40" />
                <div className="w-6 h-6 rounded-lg bg-border/50" />
              </div>
              <div className="space-y-2.5 my-auto">
                <div className="h-4 bg-border/60 rounded w-5/6" />
                <div className="h-4 bg-border/50 rounded w-4/6" />
                <div className="h-4 bg-border/40 rounded w-3/6" />
              </div>
              <div className="h-3 bg-border/40 rounded w-1/3 ml-auto" />
            </div>
          ))}
        </section>
      ) : sortedDocs.length === 0 ? (
        <section className="flex-1 flex flex-col items-center justify-center py-16 text-center">
          <div className="w-16 h-16 rounded-2xl bg-accent/10 text-accent flex items-center justify-center mb-4">
            <BookOpen className="w-8 h-8" />
          </div>
          <h3 className="text-lg font-semibold text-text mb-2">
            {docs.length === 0 ? "Your library is empty" : "No documents found"}
          </h3>
          <p className="text-sm text-muted max-w-sm mb-6">
            {docs.length === 0
              ? "Import EPUB books, PDF files, HTML pages, or TXT documents to start reading offline."
              : "No documents match the active filters. Try changing or clearing filters."}
          </p>
          {docs.length === 0 && (
            <Link
              to="/import"
              className="inline-flex items-center gap-2 px-5 py-2.5 rounded-lg bg-accent text-white font-medium text-sm hover:opacity-90 transition-opacity shadow-sm"
            >
              <Plus className="w-4 h-4" />
              <span>Import your first book</span>
            </Link>
          )}
        </section>
      ) : (
        <section className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4 sm:gap-5">
          {sortedDocs.map((doc) => (
            <BookCard
              key={doc.id}
              doc={doc}
              progress={allProgress[doc.id]}
              isSelected={selectedDocIds.has(doc.id)}
              onSelectToggle={handleSelectToggle}
              onOpen={handleOpenDoc}
              onOpenDetails={(d) => setActiveSheetDoc(d)}
              onDelete={handleDeleteDoc}
              onToggleFinished={handleToggleFinished}
              customHue={customHues[doc.id]}
              onHueChange={handleHueChange}
            />
          ))}
        </section>
      )}

      {/* Floating Batch Selection Bar */}
      {selectedDocIds.size > 0 && (
        <div className="fixed bottom-6 left-1/2 -translate-x-1/2 z-50 flex items-center gap-2 px-4 py-2.5 rounded-2xl bg-surface/95 backdrop-blur-md border border-border shadow-2xl text-xs font-medium text-text animate-in fade-in slide-in-from-bottom-4 duration-200">
          <span className="font-semibold text-accent px-2 py-1 rounded-lg bg-accent/10">
            {selectedDocIds.size} {selectedDocIds.size === 1 ? "book" : "books"} selected
          </span>
          <div className="w-px h-5 bg-border mx-1" />
          <button
            onClick={handleBatchMarkFinished}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl hover:bg-bg transition-colors"
          >
            <CheckCircle2 className="w-4 h-4 text-accent" />
            <span>Mark Finished</span>
          </button>
          <button
            onClick={handleBatchDelete}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl hover:bg-red-500/10 text-red-600 transition-colors"
          >
            <Trash2 className="w-4 h-4" />
            <span>Delete</span>
          </button>
          <button
            onClick={() => setSelectedDocIds(new Set())}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl hover:bg-bg text-muted hover:text-text transition-colors"
          >
            <span>Deselect</span>
          </button>
        </div>
      )}

      {/* Document Details Sheet */}
      <DocumentSheet
        doc={activeSheetDoc}
        open={!!activeSheetDoc}
        onOpenChange={(open) => {
          if (!open) setActiveSheetDoc(null);
        }}
        onDeleted={() => {
          setActiveSheetDoc(null);
          loadLibrary();
        }}
        onUpdated={() => {
          loadLibrary();
        }}
      />
    </div>
  );
};
