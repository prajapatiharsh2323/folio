import React, { useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import { Plus, BookOpen, Clock, BookMarked, User } from "lucide-react";
import { useLibraryStore } from "../state/library-store.js";
import type { DocMeta } from "@folio/core";

export const LibraryScreen: React.FC = () => {
  const navigate = useNavigate();
  const { docs, recentDoc, loadLibrary, sortBy, setSortBy } = useLibraryStore();

  useEffect(() => {
    loadLibrary();
  }, [loadLibrary]);

  const sortedDocs = [...docs].sort((a, b) => {
    if (sortBy === "title") return a.title.localeCompare(b.title);
    if (sortBy === "author") return (a.author || "").localeCompare(b.author || "");
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
            onChange={(e) => setSortBy(e.target.value as "recent" | "title" | "author" | "progress")}
            aria-label="Sort library documents"
            className="px-3 py-2 rounded-lg border border-border bg-surface text-text text-sm focus:outline-none focus:border-accent"
          >
            <option value="recent">Sort by Recent</option>
            <option value="title">Sort by Title</option>
            <option value="author">Sort by Author</option>
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

      {/* Continue reading row */}
      {recentDoc && (
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
                </p>
              </div>
            </div>
            <span className="text-xs font-semibold text-accent px-3 py-1.5 rounded-lg bg-accent/10">
              Resume
            </span>
          </div>
        </section>
      )}

      {/* Main library grid */}
      {sortedDocs.length === 0 ? (
        <section className="flex-1 flex flex-col items-center justify-center py-16 text-center">
          <div className="w-16 h-16 rounded-2xl bg-accent/10 text-accent flex items-center justify-center mb-4">
            <BookOpen className="w-8 h-8" />
          </div>
          <h3 className="text-lg font-semibold text-text mb-2">Your library is empty</h3>
          <p className="text-sm text-muted max-w-sm mb-6">
            Import EPUB books, PDF files, HTML pages, or TXT documents to start reading offline.
          </p>
          <Link
            to="/import"
            className="inline-flex items-center gap-2 px-5 py-2.5 rounded-lg bg-accent text-white font-medium text-sm hover:opacity-90 transition-opacity shadow-sm"
          >
            <Plus className="w-4 h-4" />
            <span>Import your first book</span>
          </Link>
        </section>
      ) : (
        <section className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {sortedDocs.map((doc) => (
            <div
              key={doc.id}
              onClick={() => handleOpenDoc(doc)}
              className="p-4 rounded-card border border-border bg-surface hover:border-accent/40 cursor-pointer transition-all flex flex-col justify-between group"
            >
              <div className="flex gap-3 mb-3">
                <div className="w-10 h-14 rounded bg-accent/10 text-accent flex items-center justify-center flex-shrink-0 group-hover:bg-accent group-hover:text-white transition-colors">
                  <BookOpen className="w-5 h-5" />
                </div>
                <div className="overflow-hidden">
                  <h4 className="font-semibold text-text text-sm leading-snug truncate">
                    {doc.title}
                  </h4>
                  <div className="flex items-center gap-1.5 text-xs text-muted mt-1">
                    <User className="w-3 h-3" />
                    <span className="truncate">{doc.author || "Unknown"}</span>
                  </div>
                </div>
              </div>

              <div className="flex items-center justify-between pt-3 border-t border-border/50 text-[11px] text-muted">
                <span>{doc.chapters.length} chapters</span>
                <span className="uppercase text-[10px] font-mono px-1.5 py-0.5 rounded bg-bg">
                  {doc.language || "en"}
                </span>
              </div>
            </div>
          ))}
        </section>
      )}
    </div>
  );
};
