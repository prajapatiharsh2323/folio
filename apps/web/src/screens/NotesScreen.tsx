import React from "react";
import { Bookmark, Download } from "lucide-react";

export const NotesScreen: React.FC = () => {
  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-3xl mx-auto w-full">
      <header className="flex items-center justify-between mb-6 pb-4 border-b border-border">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-text">Notes & Highlights</h2>
          <p className="text-sm text-muted">All saved passages, bookmarks, and thoughts</p>
        </div>
        <button
          disabled
          className="inline-flex items-center gap-2 px-3 py-1.5 rounded-lg border border-border text-muted text-sm opacity-50 cursor-not-allowed"
        >
          <Download className="w-4 h-4" />
          <span>Export Markdown</span>
        </button>
      </header>

      <div className="flex-1 flex flex-col items-center justify-center py-16 text-center text-muted">
        <Bookmark className="w-12 h-12 stroke-1 mb-3 text-muted/60" />
        <p className="text-base font-medium text-text">No annotations yet</p>
        <p className="text-sm max-w-xs mt-1">
          Select text while reading a book or article to create highlights and notes.
        </p>
      </div>
    </div>
  );
};
