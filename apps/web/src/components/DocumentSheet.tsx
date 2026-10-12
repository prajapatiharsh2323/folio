import React, { useState, useEffect } from "react";
import { Drawer } from "vaul";
import {
  X,
  Trash2,
  Save,
  FolderPlus,
  ExternalLink,
  AlertTriangle
} from "lucide-react";
import type { DocMeta, Collection } from "@folio/core";
import { libraryManager, useLibraryStore } from "../state/library-store.js";

interface DocumentSheetProps {
  doc: DocMeta | null;
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onDeleted?: () => void;
  onUpdated?: (updated: DocMeta) => void;
}

export const DocumentSheet: React.FC<DocumentSheetProps> = ({
  doc,
  open,
  onOpenChange,
  onDeleted,
  onUpdated
}) => {
  const { loadLibrary } = useLibraryStore();
  const [title, setTitle] = useState("");
  const [author, setAuthor] = useState("");
  const [tags, setTags] = useState<string[]>([]);
  const [tagInput, setTagInput] = useState("");
  const [collections, setCollections] = useState<Collection[]>([]);
  const [selectedColls, setSelectedColls] = useState<string[]>([]);
  const [newCollName, setNewCollName] = useState("");
  const [isSaving, setIsSaving] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  useEffect(() => {
    if (doc) {
      setTitle(doc.title || "");
      setAuthor(doc.author || "");
      setTags([...(doc.tags || [])]);
      setSelectedColls([...(doc.collectionIds || [])]);
      setConfirmDelete(false);

      libraryManager.getCollections().then(setCollections).catch(console.error);
    }
  }, [doc]);

  if (!doc) return null;

  const handleAddTag = (e: React.KeyboardEvent | React.MouseEvent) => {
    if ("key" in e && e.key !== "Enter") return;
    e.preventDefault();
    const clean = tagInput.trim().toLowerCase();
    if (clean && !tags.includes(clean)) {
      setTags([...tags, clean]);
      setTagInput("");
    }
  };

  const handleRemoveTag = (tagToRemove: string) => {
    setTags(tags.filter((t) => t !== tagToRemove));
  };

  const handleToggleCollection = (collId: string) => {
    if (selectedColls.includes(collId)) {
      setSelectedColls(selectedColls.filter((id) => id !== collId));
    } else {
      setSelectedColls([...selectedColls, collId]);
    }
  };

  const handleCreateCollection = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newCollName.trim()) return;
    try {
      const created = await libraryManager.createCollection(newCollName.trim());
      setCollections([...collections, created]);
      setSelectedColls([...selectedColls, created.id]);
      setNewCollName("");
    } catch (err) {
      console.error("Failed to create collection:", err);
    }
  };

  const handleSave = async () => {
    if (!doc) return;
    setIsSaving(true);
    try {
      const updated = await libraryManager.updateDocMeta(doc.id, {
        title: title.trim() || doc.title,
        author: author.trim() || undefined,
        tags,
        collectionIds: selectedColls
      });

      // Update collections store
      await libraryManager.setDocCollections(doc.id, selectedColls);

      await loadLibrary();
      onUpdated?.(updated);
      onOpenChange(false);
    } catch (err) {
      console.error("Failed to update doc:", err);
    } finally {
      setIsSaving(false);
    }
  };

  const handleDelete = async () => {
    if (!doc) return;
    try {
      await libraryManager.deleteDocument(doc.id);
      await loadLibrary();
      onDeleted?.();
      onOpenChange(false);
    } catch (err) {
      console.error("Failed to delete document:", err);
    }
  };

  return (
    <Drawer.Root open={open} onOpenChange={onOpenChange}>
      <Drawer.Portal>
        <Drawer.Overlay className="fixed inset-0 bg-black/40 backdrop-blur-sm z-50 transition-opacity" />
        <Drawer.Content className="fixed bottom-0 left-0 right-0 max-h-[90vh] bg-surface border-t border-border rounded-t-2xl z-50 flex flex-col focus:outline-none max-w-xl mx-auto shadow-2xl overflow-hidden">
          {/* Grab Handle */}
          <div className="w-12 h-1.5 bg-border rounded-full mx-auto my-3" />

          {/* Header */}
          <div className="flex items-center justify-between px-6 pb-3 border-b border-border">
            <h3 className="text-lg font-bold text-text">Document Details</h3>
            <button
              onClick={() => onOpenChange(false)}
              className="p-1 rounded-lg text-muted hover:text-text hover:bg-bg transition-colors"
            >
              <X className="w-5 h-5" />
            </button>
          </div>

          {/* Form Content */}
          <div className="flex-1 overflow-y-auto px-6 py-4 space-y-5 text-sm">
            {/* Title */}
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-muted mb-1">
                Title
              </label>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className="w-full px-3 py-2 rounded-lg border border-border bg-bg text-text focus:outline-none focus:border-accent"
              />
            </div>

            {/* Author */}
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-muted mb-1">
                Author
              </label>
              <input
                type="text"
                value={author}
                onChange={(e) => setAuthor(e.target.value)}
                placeholder="Unknown Author"
                className="w-full px-3 py-2 rounded-lg border border-border bg-bg text-text focus:outline-none focus:border-accent"
              />
            </div>

            {/* Tags */}
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-muted mb-1">
                Tags
              </label>
              <div className="flex flex-wrap gap-1.5 mb-2">
                {tags.map((tag) => (
                  <span
                    key={tag}
                    className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md bg-accent/10 text-accent text-xs font-medium"
                  >
                    #{tag}
                    <button
                      type="button"
                      onClick={() => handleRemoveTag(tag)}
                      className="hover:text-red-500 ml-0.5"
                    >
                      ×
                    </button>
                  </span>
                ))}
              </div>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={tagInput}
                  onChange={(e) => setTagInput(e.target.value)}
                  onKeyDown={handleAddTag}
                  placeholder="Add a tag..."
                  className="flex-1 px-3 py-1.5 rounded-lg border border-border bg-bg text-text text-xs focus:outline-none focus:border-accent"
                />
                <button
                  type="button"
                  onClick={handleAddTag}
                  className="px-3 py-1.5 rounded-lg border border-border text-text hover:bg-bg text-xs font-medium"
                >
                  Add
                </button>
              </div>
            </div>

            {/* Collections */}
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-muted mb-1">
                Collections
              </label>
              <div className="space-y-1.5 mb-3 max-h-32 overflow-y-auto pr-1">
                {collections.length === 0 ? (
                  <p className="text-xs text-muted">No collections created yet.</p>
                ) : (
                  collections.map((coll) => (
                    <label
                      key={coll.id}
                      className="flex items-center gap-2.5 p-2 rounded-lg border border-border/60 hover:bg-bg/60 cursor-pointer"
                    >
                      <input
                        type="checkbox"
                        checked={selectedColls.includes(coll.id)}
                        onChange={() => handleToggleCollection(coll.id)}
                        className="rounded border-border text-accent focus:ring-accent"
                      />
                      <span className="text-xs font-medium text-text">{coll.name}</span>
                    </label>
                  ))
                )}
              </div>
              <div className="flex gap-2">
                <input
                  type="text"
                  value={newCollName}
                  onChange={(e) => setNewCollName(e.target.value)}
                  placeholder="New collection name..."
                  className="flex-1 px-3 py-1.5 rounded-lg border border-border bg-bg text-text text-xs focus:outline-none focus:border-accent"
                />
                <button
                  type="button"
                  onClick={handleCreateCollection}
                  className="inline-flex items-center gap-1 px-3 py-1.5 rounded-lg bg-bg border border-border text-text hover:border-accent text-xs font-medium"
                >
                  <FolderPlus className="w-3.5 h-3.5" />
                  <span>Create</span>
                </button>
              </div>
            </div>

            {/* Metadata Info Box */}
            <div className="p-3.5 rounded-lg border border-border/80 bg-bg/50 space-y-2 text-xs text-muted">
              <div className="flex items-center justify-between">
                <span>Format:</span>
                <span className="font-medium uppercase text-text">{doc.sourceFormat}</span>
              </div>
              <div className="flex items-center justify-between">
                <span>Kind:</span>
                <span className="capitalize font-medium text-text">{doc.kind}</span>
              </div>
              {doc.sourceUrl && (
                <div className="flex items-center justify-between">
                  <span>Source URL:</span>
                  <a
                    href={doc.sourceUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex items-center gap-1 text-accent truncate max-w-[200px] hover:underline"
                  >
                    <span>{doc.sourceName || "Original link"}</span>
                    <ExternalLink className="w-3 h-3 flex-shrink-0" />
                  </a>
                </div>
              )}
              <div className="flex items-center justify-between">
                <span>Imported:</span>
                <span>{new Date(doc.importedAt).toLocaleDateString()}</span>
              </div>
              {doc.capturedAt && (
                <div className="flex items-center justify-between">
                  <span>Captured:</span>
                  <span>{new Date(doc.capturedAt).toLocaleDateString()}</span>
                </div>
              )}
              <div className="flex items-center justify-between">
                <span>Full-text Search:</span>
                <span className={doc.searchable ? "text-green-500 font-medium" : "text-amber-500"}>
                  {doc.searchable ? "Indexed & Searchable" : "No text layer"}
                </span>
              </div>
            </div>

            {/* Delete Section */}
            <div className="pt-2 border-t border-border">
              {confirmDelete ? (
                <div className="p-3 rounded-lg border border-red-500/30 bg-red-500/10 space-y-3">
                  <div className="flex items-start gap-2 text-red-500 text-xs">
                    <AlertTriangle className="w-4 h-4 flex-shrink-0 mt-0.5" />
                    <span>
                      Are you sure? This permanently deletes this document, all its files,
                      highlights, and notes offline.
                    </span>
                  </div>
                  <div className="flex gap-2 justify-end">
                    <button
                      type="button"
                      onClick={() => setConfirmDelete(false)}
                      className="px-3 py-1.5 rounded-lg border border-border text-xs text-text hover:bg-bg"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      onClick={handleDelete}
                      className="px-3 py-1.5 rounded-lg bg-red-600 text-white text-xs font-semibold hover:bg-red-700"
                    >
                      Confirm Delete
                    </button>
                  </div>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => setConfirmDelete(true)}
                  className="inline-flex items-center gap-1.5 text-xs text-red-500 hover:text-red-600 font-medium py-1"
                >
                  <Trash2 className="w-3.5 h-3.5" />
                  <span>Delete Document from Library</span>
                </button>
              )}
            </div>
          </div>

          {/* Footer Actions */}
          <div className="flex items-center justify-end gap-3 p-4 border-t border-border bg-surface">
            <button
              type="button"
              onClick={() => onOpenChange(false)}
              className="px-4 py-2 rounded-lg border border-border text-text text-sm font-medium hover:bg-bg"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={isSaving}
              className="inline-flex items-center gap-2 px-5 py-2 rounded-lg bg-accent text-white text-sm font-medium hover:opacity-90 disabled:opacity-50"
            >
              <Save className="w-4 h-4" />
              <span>{isSaving ? "Saving..." : "Save Changes"}</span>
            </button>
          </div>
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  );
};
