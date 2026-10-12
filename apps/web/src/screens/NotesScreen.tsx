import React, { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import {
  Bookmark,
  Download,
  Trash2,
  BookOpen,
  MessageSquare
} from "lucide-react";
import type { Annotation } from "@folio/core";
import { libraryManager, useLibraryStore } from "../state/library-store.js";

export const NotesScreen: React.FC = () => {
  const navigate = useNavigate();
  const { docs, loadLibrary } = useLibraryStore();
  const [annotations, setAnnotations] = useState<Annotation[]>([]);
  const [selectedDocId, setSelectedDocId] = useState<string>("all");
  const [selectedColor, setSelectedColor] = useState<string>("all");
  const [selectedKind, setSelectedKind] = useState<string>("all");

  const loadAnnotations = async () => {
    try {
      const items = await libraryManager.getAnnotations();
      setAnnotations(items);
    } catch (err) {
      console.error("Failed to load annotations:", err);
    }
  };

  useEffect(() => {
    loadLibrary();
    loadAnnotations();
  }, [loadLibrary]);

  // Filtering
  const filteredAnnotations = annotations.filter((a) => {
    if (selectedDocId !== "all" && a.docId !== selectedDocId) return false;
    if (selectedColor !== "all" && a.color !== selectedColor) return false;
    if (selectedKind !== "all" && a.kind !== selectedKind) return false;
    return true;
  });

  const handleDeleteAnnotation = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await libraryManager.deleteAnnotation(id);
      await loadAnnotations();
    } catch (err) {
      console.error("Failed to delete annotation:", err);
    }
  };

  const handleOpenPassage = (annotation: Annotation) => {
    const doc = docs.find((d) => d.id === annotation.docId);
    if (!doc) return;
    const locParam = annotation.location ? `?loc=${encodeURIComponent(annotation.location)}` : "";
    navigate(`/read/${doc.slug}${locParam}`);
  };

  const handleExportMarkdown = () => {
    if (filteredAnnotations.length === 0) return;

    let md = `# Folio Notes & Highlights Export\n\nExported on: ${new Date().toLocaleDateString()}\n\n`;

    // Group by document
    const byDoc = new Map<string, Annotation[]>();
    for (const ann of filteredAnnotations) {
      const list = byDoc.get(ann.docId) || [];
      list.push(ann);
      byDoc.set(ann.docId, list);
    }

    for (const [docId, items] of byDoc.entries()) {
      const doc = docs.find((d) => d.id === docId);
      const title = doc?.title || "Unknown Document";
      const author = doc?.author ? ` by ${doc.author}` : "";

      md += `## ${title}${author}\n\n`;

      for (const item of items) {
        md += `### ${item.kind.toUpperCase()} (${item.color || "default"})\n`;
        md += `> "${item.quote}"\n\n`;
        if (item.note) {
          md += `**Note:** ${item.note}\n\n`;
        }
        md += `*Created: ${new Date(item.createdAt).toLocaleString()}*\n\n---\n\n`;
      }
    }

    const blob = new Blob([md], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    const dateStr = new Date().toISOString().split("T")[0];
    a.href = url;
    a.download = `folio-notes-${dateStr}.md`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const getColorClasses = (color?: string) => {
    switch (color) {
      case "green":
        return "border-green-500/40 bg-green-500/10 text-green-700 dark:text-green-300";
      case "blue":
        return "border-blue-500/40 bg-blue-500/10 text-blue-700 dark:text-blue-300";
      case "pink":
        return "border-pink-500/40 bg-pink-500/10 text-pink-700 dark:text-pink-300";
      case "yellow":
      default:
        return "border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300";
    }
  };

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-4xl mx-auto w-full">
      <header className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 mb-6 pb-4 border-b border-border">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-text">Notes & Highlights</h2>
          <p className="text-sm text-muted">
            {annotations.length} saved {annotations.length === 1 ? "passage" : "passages"} across
            your offline library
          </p>
        </div>
        <button
          onClick={handleExportMarkdown}
          disabled={filteredAnnotations.length === 0}
          className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-accent text-white text-sm font-medium hover:opacity-90 transition-opacity shadow-sm disabled:opacity-50 disabled:cursor-not-allowed self-start sm:self-auto"
        >
          <Download className="w-4 h-4" />
          <span>Export Markdown</span>
        </button>
      </header>

      {/* Filter Bar */}
      <div className="flex flex-wrap items-center gap-2 mb-6 text-xs">
        {/* Document Filter */}
        <select
          value={selectedDocId}
          onChange={(e) => setSelectedDocId(e.target.value)}
          aria-label="Filter annotations by document"
          className="px-2.5 py-1.5 rounded-lg border border-border bg-surface text-text focus:outline-none focus:border-accent text-xs"
        >
          <option value="all">All Documents</option>
          {docs.map((d) => (
            <option key={d.id} value={d.id}>
              {d.title}
            </option>
          ))}
        </select>

        {/* Color Filter */}
        <select
          value={selectedColor}
          onChange={(e) => setSelectedColor(e.target.value)}
          aria-label="Filter annotations by highlight color"
          className="px-2.5 py-1.5 rounded-lg border border-border bg-surface text-text focus:outline-none focus:border-accent text-xs"
        >
          <option value="all">All Colors</option>
          <option value="yellow">Yellow</option>
          <option value="green">Green</option>
          <option value="blue">Blue</option>
          <option value="pink">Pink</option>
        </select>

        {/* Kind Filter */}
        <select
          value={selectedKind}
          onChange={(e) => setSelectedKind(e.target.value)}
          aria-label="Filter annotations by kind"
          className="px-2.5 py-1.5 rounded-lg border border-border bg-surface text-text focus:outline-none focus:border-accent text-xs"
        >
          <option value="all">All Kinds</option>
          <option value="highlight">Highlights</option>
          <option value="note">Notes</option>
          <option value="bookmark">Bookmarks</option>
        </select>
      </div>

      {/* Annotations List */}
      {filteredAnnotations.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center py-16 text-center text-muted">
          <Bookmark className="w-12 h-12 stroke-1 mb-3 text-muted/60" />
          <p className="text-base font-medium text-text">No annotations found</p>
          <p className="text-sm max-w-xs mt-1">
            {annotations.length === 0
              ? "Select text while reading a book or article to create highlights and notes."
              : "No passages match the current filters. Try changing or clearing filters."}
          </p>
        </div>
      ) : (
        <div className="space-y-4">
          {filteredAnnotations.map((ann) => {
            const doc = docs.find((d) => d.id === ann.docId);
            const isDetached = ann.location === "detached";

            return (
              <div
                key={ann.id}
                onClick={() => handleOpenPassage(ann)}
                className={`p-4 rounded-xl border transition-all cursor-pointer hover:shadow-sm ${getColorClasses(
                  ann.color
                )}`}
              >
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div className="flex items-center gap-2">
                    <BookOpen className="w-4 h-4 opacity-75 flex-shrink-0" />
                    <span className="font-semibold text-text text-sm">
                      {doc?.title || "Unknown Document"}
                    </span>
                    {isDetached && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] font-mono bg-red-500/20 text-red-600 font-semibold">
                        Detached
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    <span className="text-[11px] opacity-75">
                      {new Date(ann.createdAt).toLocaleDateString()}
                    </span>
                    <button
                      onClick={(e) => handleDeleteAnnotation(ann.id, e)}
                      title="Delete annotation"
                      className="p-1 rounded hover:bg-black/10 dark:hover:bg-white/10 opacity-70 hover:opacity-100 transition-opacity"
                    >
                      <Trash2 className="w-3.5 h-3.5" />
                    </button>
                  </div>
                </div>

                {/* Quote */}
                <blockquote className="text-sm leading-relaxed italic border-l-2 pl-3 my-2 opacity-95">
                  &ldquo;{ann.quote}&rdquo;
                </blockquote>

                {/* Optional Note */}
                {ann.note && (
                  <div className="mt-2.5 pt-2 border-t border-black/10 dark:border-white/10 flex items-start gap-2 text-xs">
                    <MessageSquare className="w-3.5 h-3.5 flex-shrink-0 mt-0.5 opacity-75" />
                    <p className="text-text font-medium">{ann.note}</p>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};
