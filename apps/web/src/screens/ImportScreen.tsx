import React, { useState, useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { UploadCloud, CheckCircle2, AlertCircle, FileText, ArrowLeft } from "lucide-react";
import {
  detectFileFormat,
  importEpub,
  importPdf,
  importHtml,
  importTxt,
  type ImportResult
} from "@folio/core";
import { libraryManager, useLibraryStore } from "../state/library-store.js";
import { searchClient } from "../search/search-client.js";

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

  useEffect(() => {
    // Check Cache Storage for files queued by PWA share-target
    if (typeof caches !== "undefined") {
      caches
        .open("folio-share-target")
        .then(async (cache) => {
          const requests = await cache.keys();
          if (requests.length === 0) return;
          const sharedFiles: File[] = [];
          for (const req of requests) {
            const res = await cache.match(req);
            if (res) {
              const blob = await res.blob();
              const rawName = res.headers.get("X-Filename") || "shared-file";
              const filename = decodeURIComponent(rawName);
              sharedFiles.push(new File([blob], filename, { type: blob.type }));
              await cache.delete(req);
            }
          }
          if (sharedFiles.length > 0) {
            processFiles(sharedFiles);
          }
        })
        .catch(console.error);
    }

    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const queued = (window as any).__FOLIO_QUEUED_FILES__ as File[] | undefined;
    if (queued && queued.length > 0) {
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      (window as any).__FOLIO_QUEUED_FILES__ = undefined;
      processFiles(queued);
    }

    const handleQueuedEvent = (e: Event) => {
      const custom = e as CustomEvent<File[]>;
      if (custom.detail && custom.detail.length > 0) {
        processFiles(custom.detail);
      }
    };

    window.addEventListener("folio:files-queued", handleQueuedEvent);
    return () => window.removeEventListener("folio:files-queued", handleQueuedEvent);
  }, []);

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

        // Section 6: Detect by content, never by extension
        const format = detectFileFormat(bytes);
        let importResult: ImportResult;

        if (format === "epub") {
          importResult = await importEpub(bytes, file.name);
        } else if (format === "pdf") {
          importResult = await importPdf(bytes, file.name);
        } else if (format === "html") {
          importResult = await importHtml(bytes, file.name);
        } else if (format === "txt") {
          importResult = await importTxt(bytes, file.name);
        } else {
          throw new Error("Unsupported file type. Expected EPUB, PDF, HTML, or TXT.");
        }

        // Section 4 Rule 3: Duplicate detection
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

        // Commit to library
        const docMeta = await libraryManager.addDocument(importResult);
        addDocumentToState(docMeta);

        // Warm up search worker
        if (importResult.searchText && importResult.searchText.length > 0) {
          const entries = importResult.searchText.map((st) => ({
            id: `${docMeta.id}:${st.chapterId}`,
            docId: docMeta.id,
            chapterId: st.chapterId,
            title: docMeta.title,
            chapterTitle: st.title,
            author: docMeta.author || "",
            tags: docMeta.tags,
            text: st.text
          }));
          await searchClient.indexDocs(entries);
        }

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

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    if (e.dataTransfer.files) {
      processFiles(e.dataTransfer.files);
    }
  };

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-3xl mx-auto w-full">
      <header className="flex items-center gap-3 mb-6">
        <button
          onClick={() => navigate("/")}
          className="p-2 rounded-lg hover:bg-surface text-muted hover:text-text transition-colors"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-text">Import Documents</h2>
          <p className="text-sm text-muted">
            Import EPUB books, PDF files, HTML articles, or TXT documents
          </p>
        </div>
      </header>

      {/* Drag & Drop Zone */}
      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={handleDrop}
        className={`border-2 border-dashed rounded-2xl p-8 sm:p-12 text-center transition-all cursor-pointer mb-8 ${
          dragOver
            ? "border-accent bg-accent/10 scale-[0.99]"
            : "border-border bg-surface hover:border-accent/50"
        }`}
        onClick={() => document.getElementById("file-input")?.click()}
      >
        <div className="w-14 h-14 rounded-full bg-accent/15 text-accent flex items-center justify-center mx-auto mb-4">
          <UploadCloud className="w-7 h-7" />
        </div>
        <h3 className="text-base font-semibold text-text mb-1">
          Drag and drop documents here
        </h3>
        <p className="text-xs text-muted mb-4 max-w-sm mx-auto">
          Supports EPUB books, PDF documents, HTML pages, and plain text files. Files are verified
          and stored completely offline.
        </p>
        <button
          type="button"
          disabled={isProcessing}
          className="px-4 py-2 rounded-lg bg-accent text-white text-xs font-medium hover:opacity-90 shadow-sm"
        >
          Select Files
        </button>
        <input
          id="file-input"
          type="file"
          multiple
          accept=".epub,.pdf,.html,.htm,.txt,application/epub+zip,application/pdf,text/html,text/plain"
          className="hidden"
          onChange={(e) => {
            if (e.target.files) processFiles(e.target.files);
          }}
        />
      </div>

      {/* Batch Import Progress List */}
      {items.length > 0 && (
        <div className="rounded-card border border-border bg-surface p-4 mb-8">
          <h4 className="text-xs font-semibold uppercase tracking-wider text-muted mb-3">
            Import Batch Queue ({items.length})
          </h4>
          <div className="divide-y divide-border/60">
            {items.map((item, idx) => (
              <div key={idx} className="py-2.5 flex items-center justify-between text-xs">
                <div className="flex items-center gap-2.5 overflow-hidden">
                  <FileText className="w-4 h-4 text-muted flex-shrink-0" />
                  <span className="font-medium text-text truncate max-w-xs sm:max-w-md">
                    {item.filename}
                  </span>
                </div>
                <div>
                  {item.status === "pending" && (
                    <span className="text-muted">Queued</span>
                  )}
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

      {/* Sample Fixtures Quick-Load */}
      <div className="p-4 rounded-card border border-border bg-surface/70 space-y-3">
        <h4 className="text-xs font-semibold uppercase tracking-wider text-muted">
          Quick Test Fixtures
        </h4>
        <div className="flex flex-wrap gap-2">
          <button
            onClick={async () => {
              const res = await fetch("/fixtures/english-novel.epub");
              const blob = await res.blob();
              processFiles([
                new File([blob], "english-novel.epub", { type: "application/epub+zip" })
              ]);
            }}
            className="px-3 py-1.5 rounded-lg border border-border bg-bg hover:border-accent text-xs font-medium"
          >
            Import English Novel
          </button>
          <button
            onClick={async () => {
              const res = await fetch("/fixtures/hindi-book.epub");
              const blob = await res.blob();
              processFiles([
                new File([blob], "hindi-book.epub", { type: "application/epub+zip" })
              ]);
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
