import React, { useEffect, useState, useCallback } from "react";
import { useNavigate } from "react-router-dom";
import {
  Inbox,
  Check,
  Trash2,
  ExternalLink,
  User,
  Calendar,
  AlertCircle,
  RefreshCw
} from "lucide-react";
import type { ClipPayload, ProcessedClip, ManifestDoc } from "@folio/core";
import { processClip } from "@folio/core";
import { libraryManager, useLibraryStore } from "../state/library-store.js";
import { searchClient } from "../search/search-client.js";

interface PendingClip extends ProcessedClip {
  id: string;
  tags: string[];
}

function extractCleanSearchText(html: string): string {
  return html
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<[^>]+>/g, " ")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/\s+/g, " ")
    .trim();
}

export const ClipInboxScreen: React.FC = () => {
  const navigate = useNavigate();
  const [pendingClips, setPendingClips] = useState<PendingClip[]>(() => {
    try {
      const stored = sessionStorage.getItem("folio:pending-clips");
      return stored ? JSON.parse(stored) : [];
    } catch {
      return [];
    }
  });
  const [selectedClipId, setSelectedClipId] = useState<string | null>(null);
  const [duplicateDoc, setDuplicateDoc] = useState<ManifestDoc | null>(null);

  // Sync pending clips to sessionStorage so they persist across screen changes
  useEffect(() => {
    try {
      sessionStorage.setItem("folio:pending-clips", JSON.stringify(pendingClips));
    } catch {
      // storage limit or quota
    }
  }, [pendingClips]);

  const handleIncomingPayload = useCallback(async (payload: ClipPayload) => {
    if (!payload || !payload.contentHtml) return;
    try {
      const processed = await processClip(payload);
      const newClip: PendingClip = {
        ...processed,
        id: crypto.randomUUID(),
        tags: []
      };
      setPendingClips((prev) => [newClip, ...prev]);
      setSelectedClipId(newClip.id);

      // Check if duplicate exists
      const existing = await libraryManager.findDuplicate(processed.contentHash);
      if (existing) {
        setDuplicateDoc(existing);
      } else {
        setDuplicateDoc(null);
      }
    } catch (err) {
      console.error("Failed to process incoming clip:", err);
    }
  }, []);

  // Section 6 clip receiving:
  // main.tsx manages the global window.__FOLIO_CLIP_NONCE__ and delivers verified clips via
  // sessionStorage ("folio:raw-clips") and "folio:clip-received" CustomEvent.
  useEffect(() => {
    // Drain any raw clips queued while inbox was not mounted
    try {
      const rawQueue = JSON.parse(sessionStorage.getItem("folio:raw-clips") || "[]") as ClipPayload[];
      if (rawQueue.length > 0) {
        sessionStorage.removeItem("folio:raw-clips");
        for (const queued of rawQueue) {
          handleIncomingPayload(queued);
        }
      }
    } catch {
      // ignore
    }

    const handleCustomClip = (e: Event) => {
      const custom = e as CustomEvent<ClipPayload>;
      if (custom.detail) {
        handleIncomingPayload(custom.detail);
      }
    };

    window.addEventListener("folio:clip-received", handleCustomClip);
    return () => {
      window.removeEventListener("folio:clip-received", handleCustomClip);
    };
  }, [handleIncomingPayload]);

  const activeClip = pendingClips.find((c) => c.id === selectedClipId) || pendingClips[0];

  // Re-check duplicate status when active clip changes
  useEffect(() => {
    if (!activeClip) {
      setDuplicateDoc(null);
      return;
    }
    libraryManager.findDuplicate(activeClip.contentHash).then((existing) => {
      setDuplicateDoc(existing || null);
    });
  }, [activeClip]);

  const handleSaveActiveClip = async () => {
    if (!activeClip) return;

    try {
      const cleanSearchText = extractCleanSearchText(activeClip.cleanHtml);

      // Add document via libraryManager following Section 4 write order rules
      const doc = await libraryManager.addDocument({
        meta: {
          schema: 1,
          title: activeClip.title,
          author: activeClip.author,
          sourceName: activeClip.siteName,
          sourceUrl: activeClip.sourceUrl,
          kind: "article",
          language: activeClip.lang,
          sourceFormat: "clip",
          capturedAt: new Date().toISOString(),
          contentHash: activeClip.contentHash,
          searchable: true,
          tags: activeClip.tags,
          collectionIds: [],
          chapters: [
            {
              id: "ch-1",
              title: activeClip.title,
              order: 1,
              href: "clean/index.html"
            }
          ]
        },
        original: {
          name: "clip.html",
          bytes: new TextEncoder().encode(activeClip.cleanIndexHtml)
        },
        clean: {
          indexHtml: activeClip.cleanIndexHtml,
          images: activeClip.images
        },
        reading: {
          name: "book.epub",
          bytes: activeClip.readingEpub
        },
        searchText: [
          {
            chapterId: "ch-1",
            title: activeClip.title,
            text: cleanSearchText
          }
        ]
      });

      // Index document in search worker
      try {
        await searchClient.indexDocs([
          {
            id: `${doc.id}:ch-1`,
            docId: doc.id,
            chapterId: "ch-1",
            title: doc.title,
            chapterTitle: doc.title,
            author: doc.author || "",
            tags: doc.tags || [],
            text: cleanSearchText
          }
        ]);
      } catch (err) {
        console.warn("Failed to index saved clip:", err);
      }

      // Update library store
      await useLibraryStore.getState().loadLibrary();

      // Remove from inbox
      setPendingClips((prev) => prev.filter((c) => c.id !== activeClip.id));
      navigate(`/read/${doc.slug}`);
    } catch (err) {
      console.error("Failed to save clip:", err);
    }
  };

  const handleReplaceExisting = async () => {
    if (!activeClip || !duplicateDoc) return;
    try {
      await libraryManager.deleteDocument(duplicateDoc.id);
      await handleSaveActiveClip();
    } catch (err) {
      console.error("Failed to replace existing document:", err);
    }
  };

  const handleDiscard = (id: string) => {
    setPendingClips((prev) => prev.filter((c) => c.id !== id));
    if (selectedClipId === id) {
      setSelectedClipId(null);
    }
  };

  // Test demo clip loader
  const handleLoadDemoClip = async () => {
    const demoPayload: ClipPayload = {
      version: 1,
      url: "https://example.com/craft-of-reading",
      title: "The Subtle Craft of Reading Offline",
      byline: "Elena Rostova",
      siteName: "Literary Gazette",
      lang: "en",
      contentHtml: `
        <p>In an age of relentless notification streams, holding an offline book or article represents an intentional sanctuary for the mind.</p>
        <blockquote>"To read without interruption is to inhabit a quiet room where thoughts can mature at their natural pace."</blockquote>
        <p>Folio stores pure local files in your private sandbox, allowing focused contemplation without algorithms.</p>
      `,
      images: []
    };

    handleIncomingPayload(demoPayload);
  };

  return (
    <div className="flex-1 flex flex-col h-full bg-bg text-text max-w-6xl mx-auto w-full p-4 md:p-8">
      <header className="flex flex-col sm:flex-row sm:items-center justify-between pb-4 border-b border-border mb-6 gap-3">
        <div>
          <h2 className="text-2xl font-bold tracking-tight text-text">Clip Inbox</h2>
          <p className="text-sm text-muted">
            Articles received from your Windows extension or clipboard
          </p>
        </div>
        <button
          onClick={handleLoadDemoClip}
          className="px-3 py-1.5 rounded-lg border border-border bg-surface text-xs font-medium hover:border-accent"
        >
          Simulate Sample Web Clip
        </button>
      </header>

      {duplicateDoc && (
        <div className="mb-4 p-3.5 rounded-lg border border-amber-500/40 bg-amber-500/10 text-amber-800 dark:text-amber-200 text-xs flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <AlertCircle className="w-4 h-4 flex-shrink-0 text-amber-600 dark:text-amber-400" />
            <span>
              A document with matching content already exists in your library: <strong>&ldquo;{duplicateDoc.title}&rdquo;</strong>.
            </span>
          </div>
          <div className="flex items-center gap-2 shrink-0">
            <button
              onClick={() => activeClip && handleDiscard(activeClip.id)}
              className="px-2.5 py-1 rounded bg-surface border border-border text-muted hover:text-text font-medium text-[11px]"
            >
              Skip / Discard
            </button>
            <button
              onClick={handleReplaceExisting}
              className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-amber-600 text-white font-medium hover:bg-amber-700 text-[11px]"
            >
              <RefreshCw className="w-3 h-3" />
              <span>Replace Existing</span>
            </button>
          </div>
        </div>
      )}

      {pendingClips.length === 0 ? (
        <div className="flex-1 flex flex-col items-center justify-center py-20 text-center">
          <div className="w-16 h-16 rounded-2xl bg-accent/10 text-accent flex items-center justify-center mb-4">
            <Inbox className="w-8 h-8" />
          </div>
          <h3 className="text-lg font-semibold text-text mb-1">Inbox is empty</h3>
          <p className="text-sm text-muted max-w-sm mb-4">
            Use the Folio Clipper extension on Windows (Alt+Shift+F) or right-click any page to clip articles here.
          </p>
        </div>
      ) : (
        <div className="flex-1 flex flex-col lg:flex-row gap-6 min-h-0 overflow-hidden">
          {/* Clips List */}
          <div className="w-full lg:w-72 border border-border rounded-card bg-surface overflow-y-auto divide-y divide-border">
            {pendingClips.map((clip) => (
              <div
                key={clip.id}
                onClick={() => setSelectedClipId(clip.id)}
                className={`p-3.5 cursor-pointer transition-colors ${
                  activeClip?.id === clip.id ? "bg-accent/10" : "hover:bg-bg"
                }`}
              >
                <h4 className="font-semibold text-xs text-text truncate mb-1">{clip.title}</h4>
                <p className="text-[11px] text-muted truncate">
                  {clip.author || clip.siteName || "Web article"}
                </p>
              </div>
            ))}
          </div>

          {/* Active Clip Preview & Metadata Form */}
          {activeClip && (
            <div className="flex-1 flex flex-col border border-border rounded-card bg-surface overflow-hidden">
              {/* Toolbar */}
              <div className="p-4 border-b border-border flex flex-wrap items-center justify-between gap-3 bg-bg/50">
                <div className="flex items-center gap-4 text-xs text-muted">
                  <span className="flex items-center gap-1">
                    <User className="w-3.5 h-3.5" />
                    {activeClip.author || "Unknown"}
                  </span>
                  <a
                    href={activeClip.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="flex items-center gap-1 hover:text-accent truncate max-w-xs"
                  >
                    <ExternalLink className="w-3.5 h-3.5" />
                    {activeClip.siteName || "Source Link"}
                  </a>
                  <span className="flex items-center gap-1">
                    <Calendar className="w-3.5 h-3.5" />
                    Today
                  </span>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={() => handleDiscard(activeClip.id)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border border-border text-xs text-muted hover:text-red-500 hover:border-red-500/50 transition-colors"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    <span>Discard</span>
                  </button>
                  <button
                    onClick={handleSaveActiveClip}
                    className="inline-flex items-center gap-1.5 px-4 py-1.5 rounded-lg bg-accent text-white font-medium text-xs hover:opacity-90 transition-opacity shadow-sm"
                  >
                    <Check className="w-3.5 h-3.5" />
                    <span>Save to Library</span>
                  </button>
                </div>
              </div>

              {/* Editable Fields */}
              <div className="p-4 border-b border-border bg-surface grid grid-cols-1 md:grid-cols-2 gap-3 text-xs">
                <div>
                  <label className="font-semibold text-muted block mb-1">Title</label>
                  <input
                    type="text"
                    value={activeClip.title}
                    onChange={(e) => {
                      const updated = { ...activeClip, title: e.target.value };
                      setPendingClips((prev) => prev.map((c) => (c.id === activeClip.id ? updated : c)));
                    }}
                    className="w-full px-3 py-1.5 border border-border rounded-lg bg-bg text-text"
                  />
                </div>
                <div>
                  <label className="font-semibold text-muted block mb-1">Author</label>
                  <input
                    type="text"
                    value={activeClip.author || ""}
                    onChange={(e) => {
                      const updated = { ...activeClip, author: e.target.value };
                      setPendingClips((prev) => prev.map((c) => (c.id === activeClip.id ? updated : c)));
                    }}
                    className="w-full px-3 py-1.5 border border-border rounded-lg bg-bg text-text"
                  />
                </div>
              </div>

              {/* Clean Preview Content */}
              <div className="flex-1 p-6 overflow-y-auto bg-surface prose prose-sm max-w-none text-text">
                <div
                  dangerouslySetInnerHTML={{ __html: activeClip.cleanHtml }}
                  className="font-serif leading-relaxed"
                />
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};
