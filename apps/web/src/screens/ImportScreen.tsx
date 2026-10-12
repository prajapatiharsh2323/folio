import React, { useState } from "react";
import { useNavigate } from "react-router-dom";
import { UploadCloud, CheckCircle2, AlertCircle, FileText, ArrowLeft } from "lucide-react";
import { importEpub } from "@folio/core";
import { libraryManager, useLibraryStore } from "../state/library-store.js";

interface ImportStatus {
  filename: string;
  status: "pending" | "processing" | "success" | "duplicate" | "error";
  message?: string;
}

export const ImportScreen: React.FC = () => {
  const navigate = useNavigate();
  const [items, setItems] = useState<ImportStatus[]>([]);
  const [isProcessing, setIsProcessing] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const addDocumentToState = useLibraryStore((state) => state.addDocument);

  const processFiles = async (files: FileList | File[]) => {
    const fileList = Array.from(files);
    if (fileList.length === 0) return;

    setIsProcessing(true);
    const initialStatuses: ImportStatus[] = fileList.map((f) => ({
      filename: f.name,
      status: "pending"
    }));
    setItems(initialStatuses);

    for (let i = 0; i < fileList.length; i++) {
      const file = fileList[i]!;
      setItems((prev) =>
        prev.map((item, idx) => (idx === i ? { ...item, status: "processing" } : item))
      );

      try {
        const arrayBuffer = await file.arrayBuffer();
        const bytes = new Uint8Array(arrayBuffer);

        // Check if EPUB
        if (file.name.toLowerCase().endsWith(".epub") || file.type.includes("epub")) {
          const importResult = await importEpub(bytes, file.name);

          // Duplicate detection
          const existing = await libraryManager.findDuplicate(importResult.meta.contentHash);
          if (existing) {
            setItems((prev) =>
              prev.map((item, idx) =>
                idx === i
                  ? {
                      ...item,
                      status: "duplicate",
                      message: `Already in library as "${existing.title}".`
                    }
                  : item
              )
            );
            continue;
          }

          const docMeta = await libraryManager.addDocument(importResult);
          addDocumentToState(docMeta);

          setItems((prev) =>
            prev.map((item, idx) =>
              idx === i
                ? {
                    ...item,
                    status: "success",
                    message: `Imported "${docMeta.title}".`
                  }
                : item
            )
          );
        } else {
          setItems((prev) =>
            prev.map((item, idx) =>
              idx === i
                ? {
                    ...item,
                    status: "error",
                    message: "Unsupported format for Phase 1. (.epub only)"
                  }
                : item
            )
          );
        }
      } catch (err) {
        const error = err as Error;
        setItems((prev) =>
          prev.map((item, idx) =>
            idx === i
              ? {
                  ...item,
                  status: "error",
                  message: error.message || "Import failed"
                }
              : item
          )
        );
      }
    }

    setIsProcessing(false);
  };

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-3xl mx-auto w-full">
      <header className="flex items-center gap-4 mb-6 pb-4 border-b border-border">
        <button
          onClick={() => navigate("/")}
          className="p-2 rounded-lg hover:bg-surface border border-border text-muted hover:text-text transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-text">Import Documents</h2>
          <p className="text-sm text-muted">Add EPUB books with zero cloud upload</p>
        </div>
      </header>

      {/* Drag & Drop Target */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault();
          setDragOver(false);
          if (e.dataTransfer.files) {
            processFiles(e.dataTransfer.files);
          }
        }}
        className={`border-2 border-dashed rounded-card flex flex-col items-center justify-center p-8 text-center transition-colors mb-6 ${
          dragOver ? "border-accent bg-accent/5" : "border-border bg-surface/50"
        }`}
      >
        <div className="w-14 h-14 rounded-2xl bg-accent/10 text-accent flex items-center justify-center mb-4">
          <UploadCloud className="w-7 h-7" />
        </div>
        <h3 className="text-base font-semibold text-text mb-1">Select EPUB files or drop them here</h3>
        <p className="text-xs text-muted mb-6">Import batches smoothly with duplicate detection</p>

        <label className="cursor-pointer inline-flex items-center gap-2 px-5 py-2.5 rounded-lg bg-accent text-white font-medium text-sm hover:opacity-90 transition-opacity">
          <input
            type="file"
            multiple
            accept=".epub,application/epub+zip"
            className="hidden"
            disabled={isProcessing}
            onChange={(e) => {
              if (e.target.files) {
                processFiles(e.target.files);
              }
            }}
          />
          <FileText className="w-4 h-4" />
          <span>Browse EPUB files</span>
        </label>
      </div>

      {/* Batch Import Progress and Summary */}
      {items.length > 0 && (
        <div className="space-y-2 mb-6">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-muted">
            Batch Import Status
          </h4>
          <div className="divide-y divide-border border border-border rounded-lg bg-surface overflow-hidden">
            {items.map((item, index) => (
              <div key={index} className="p-3 flex items-center justify-between text-xs">
                <div className="flex items-center gap-2 overflow-hidden">
                  <FileText className="w-4 h-4 text-muted flex-shrink-0" />
                  <span className="font-medium text-text truncate max-w-xs">{item.filename}</span>
                </div>
                <div className="flex items-center gap-2">
                  {item.status === "processing" && (
                    <span className="text-accent font-medium animate-pulse">Processing...</span>
                  )}
                  {item.status === "success" && (
                    <span className="text-green-600 flex items-center gap-1 font-medium">
                      <CheckCircle2 className="w-3.5 h-3.5" /> Done
                    </span>
                  )}
                  {item.status === "duplicate" && (
                    <span className="text-amber-600 flex items-center gap-1 font-medium">
                      <AlertCircle className="w-3.5 h-3.5" /> {item.message}
                    </span>
                  )}
                  {item.status === "error" && (
                    <span className="text-red-500 flex items-center gap-1 font-medium">
                      <AlertCircle className="w-3.5 h-3.5" /> {item.message}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Sample Fixtures Quick-Load (for testing acceptance) */}
      <div className="p-4 rounded-card border border-border bg-surface/70 space-y-3">
        <h4 className="text-xs font-semibold uppercase tracking-wider text-muted">
          Quick Test Fixtures
        </h4>
        <div className="flex flex-wrap gap-2">
          <button
            onClick={async () => {
              const res = await fetch("/fixtures/english-novel.epub");
              const blob = await res.blob();
              processFiles([new File([blob], "english-novel.epub", { type: "application/epub+zip" })]);
            }}
            className="px-3 py-1.5 rounded-lg border border-border bg-bg hover:border-accent text-xs font-medium"
          >
            Import English Novel
          </button>
          <button
            onClick={async () => {
              const res = await fetch("/fixtures/hindi-book.epub");
              const blob = await res.blob();
              processFiles([new File([blob], "hindi-book.epub", { type: "application/epub+zip" })]);
            }}
            className="px-3 py-1.5 rounded-lg border border-border bg-bg hover:border-accent text-xs font-medium"
          >
            Import Hindi Book
          </button>
        </div>
      </div>
    </div>
  );
};
