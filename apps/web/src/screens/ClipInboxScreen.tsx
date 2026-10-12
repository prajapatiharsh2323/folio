import React, { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  Inbox,
  Check,
  Trash2,
  ExternalLink,
  User,
  Calendar,
  AlertCircle
} from "lucide-react";
import type { ClipPayload, ProcessedClip } from "@folio/core";
import { processClip } from "@folio/core";
import { libraryManager } from "../state/library-store.js";

interface PendingClip extends ProcessedClip {
  id: string;
  tags: string[];
}

export const ClipInboxScreen: React.FC = () => {
  const navigate = useNavigate();
  const [pendingClips, setPendingClips] = useState<PendingClip[]>([]);
  const [selectedClipId, setSelectedClipId] = useState<string | null>(null);
  const [duplicateWarning, setDuplicateWarning] = useState<string | null>(null);

  // Section 6 bridge listener:
  // Receives articles from clipper with event.source === window, event.origin === location.origin, and nonce check
  useEffect(() => {
    // Generate one-time bridge nonce and publish on window
    const bridgeNonce = crypto.randomUUID();
    (window as unknown as { __FOLIO_CLIP_NONCE__?: string }).__FOLIO_CLIP_NONCE__ = bridgeNonce;

    const handleMessage = async (event: MessageEvent) => {
      if (event.source !== window || event.origin !== window.location.origin) {
        return;
      }
      if (!event.data || event.data.type !== "FOLIO_CLIP_DELIVERY") {
        return;
      }
      if (event.data.nonce !== bridgeNonce) {
        console.warn("Clip delivery rejected: nonce mismatch");
        return;
      }

      const payload = event.data.payload as ClipPayload;
      if (payload && payload.contentHtml) {
        try {
          const processed = await processClip(payload);
          const newClip: PendingClip = {
            ...processed,
            id: crypto.randomUUID(),
            tags: []
          };
          setPendingClips((prev) => [newClip, ...prev]);
          setSelectedClipId(newClip.id);

          // Check if hash already exists
          const existing = await libraryManager.findDuplicate(processed.contentHash);
          if (existing) {
            setDuplicateWarning(`A document with matching content already exists: "${existing.title}".`);
          } else {
            setDuplicateWarning(null);
          }
        } catch (err) {
          console.error("Failed to process incoming clip:", err);
        }
      }
    };

    window.addEventListener("message", handleMessage);
    return () => window.removeEventListener("message", handleMessage);
  }, []);

  const activeClip = pendingClips.find((c) => c.id === selectedClipId) || pendingClips[0];

  const handleSaveActiveClip = async () => {
    if (!activeClip) return;

    try {
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
            text: activeClip.cleanHtml.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim()
          }
        ]
      });

      // Remove from inbox
      setPendingClips((prev) => prev.filter((c) => c.id !== activeClip.id));
      navigate(`/read/${doc.slug}`);
    } catch (err) {
      console.error("Failed to save clip:", err);
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

    const processed = await processClip(demoPayload);
    const newClip: PendingClip = {
      ...processed,
      id: crypto.randomUUID(),
      tags: ["reading", "calm"]
    };
    setPendingClips((prev) => [newClip, ...prev]);
    setSelectedClipId(newClip.id);
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

      {duplicateWarning && (
        <div className="mb-4 p-3 rounded-lg border border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300 text-xs flex items-center gap-2">
          <AlertCircle className="w-4 h-4 flex-shrink-0" />
          <span>{duplicateWarning}</span>
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
