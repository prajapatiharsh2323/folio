import React, { useEffect, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import {
  Plus,
  BookOpen,
  Clock,
  BookMarked,
  User,
  MoreVertical,
  Tag,
  FolderOpen
} from "lucide-react";
import { useLibraryStore, libraryManager } from "../state/library-store.js";
import type { DocMeta, Collection, Progress } from "@folio/core";
import { DocumentSheet } from "../components/DocumentSheet.js";

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
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-text">Library</h2>
          <p className="text-sm text-muted">
            {docs.length} {docs.length === 1 ? "document" : "documents"} offline
          </p>
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
        <section className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 animate-pulse">
          {[1, 2, 3, 4, 5, 6].map((i) => (
            <div
              key={i}
              className="p-4 rounded-card border border-border/60 bg-surface/60 flex flex-col justify-between h-36"
            >
              <div className="flex gap-3">
                <div className="w-10 h-14 rounded bg-border/60 flex-shrink-0" />
                <div className="flex-1 space-y-2 py-1">
                  <div className="h-4 bg-border/60 rounded w-3/4" />
                  <div className="h-3 bg-border/40 rounded w-1/2" />
                </div>
              </div>
              <div className="flex items-center justify-between pt-3 border-t border-border/30">
                <div className="h-2.5 bg-border/40 rounded w-1/4" />
                <div className="h-2.5 bg-border/40 rounded w-12" />
              </div>
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
        <section className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {sortedDocs.map((doc) => {
            const progress = allProgress[doc.id];
            return (
              <div
                key={doc.id}
                className="p-4 rounded-card border border-border bg-surface hover:border-accent/40 transition-all flex flex-col justify-between group relative"
              >
                <div
                  onClick={() => handleOpenDoc(doc)}
                  className="cursor-pointer flex gap-3 mb-3"
                >
                  <div className="w-10 h-14 rounded bg-accent/10 text-accent flex items-center justify-center flex-shrink-0 group-hover:bg-accent group-hover:text-white transition-colors">
                    <BookOpen className="w-5 h-5" />
                  </div>
                  <div className="overflow-hidden flex-1 pr-6">
                    <h4 className="font-semibold text-text text-sm leading-snug truncate">
                      {doc.title}
                    </h4>
                    <div className="flex items-center gap-1.5 text-xs text-muted mt-1">
                      <User className="w-3 h-3 flex-shrink-0" />
                      <span className="truncate">{doc.author || "Unknown"}</span>
                    </div>
                    {doc.tags && doc.tags.length > 0 && (
                      <div className="flex items-center gap-1 mt-1.5 overflow-hidden">
                        <Tag className="w-2.5 h-2.5 text-muted flex-shrink-0" />
                        <span className="text-[11px] text-accent truncate">
                          {doc.tags.map((t) => `#${t}`).join(" ")}
                        </span>
                      </div>
                    )}
                  </div>
                </div>

                {/* More / Settings icon button */}
                <button
                  type="button"
                  aria-label="Document options"
                  onClick={(e) => {
                    e.stopPropagation();
                    setActiveSheetDoc(doc);
                  }}
                  className="absolute top-3 right-3 p-1.5 rounded-lg text-muted hover:text-text hover:bg-bg transition-colors"
                >
                  <MoreVertical className="w-4 h-4" />
                </button>

                {/* Progress bar if read */}
                {progress && (
                  <div className="w-full bg-border h-1 rounded-full mb-3 overflow-hidden">
                    <div
                      className="bg-accent h-full transition-all"
                      style={{ width: `${progress.percent}%` }}
                    />
                  </div>
                )}

                <div className="flex items-center justify-between pt-3 border-t border-border/50 text-[11px] text-muted">
                  <span>
                    {progress ? `${progress.percent}% read` : `${doc.chapters.length} chapters`}
                  </span>
                  <div className="flex items-center gap-1.5">
                    <span className="uppercase text-[10px] font-mono px-1.5 py-0.5 rounded bg-bg text-muted">
                      {doc.sourceFormat}
                    </span>
                    <span className="uppercase text-[10px] font-mono px-1.5 py-0.5 rounded bg-bg text-muted">
                      {doc.language || "en"}
                    </span>
                  </div>
                </div>
              </div>
            );
          })}
        </section>
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
