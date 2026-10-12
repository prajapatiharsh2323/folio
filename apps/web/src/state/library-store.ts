import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { DocMeta, Progress, ReadingPrefs, FileStore } from "@folio/core";
import { OpfsStore, MemoryStore, LibraryManager } from "@folio/core";

// Storage provider for web
const isOpfsSupported = typeof navigator !== "undefined" && !!navigator.storage?.getDirectory;
export let fileStore: FileStore = isOpfsSupported ? new OpfsStore() : new MemoryStore();
export let libraryManager = new LibraryManager(fileStore);

export function setCustomFileStore(newStore: FileStore): void {
  fileStore = newStore;
  libraryManager = new LibraryManager(newStore);
}

interface LibraryState {
  docs: DocMeta[];
  recentDoc: DocMeta | null;
  loading: boolean;
  sortBy: "recent" | "title" | "author" | "progress";
  filterKind: "all" | "book" | "article";
  loadLibrary: () => Promise<void>;
  addDocument: (doc: DocMeta) => void;
  setSortBy: (sort: "recent" | "title" | "author" | "progress") => void;
  setFilterKind: (kind: "all" | "book" | "article") => void;
}

export const useLibraryStore = create<LibraryState>((set) => ({
  docs: [],
  recentDoc: null,
  loading: false,
  sortBy: "recent",
  filterKind: "all",

  loadLibrary: async () => {
    set({ loading: true });
    try {
      const docs = await libraryManager.listDocuments();
      const recent = docs.length > 0 ? docs[0]! : null;
      set({ docs, recentDoc: recent, loading: false });
    } catch (err) {
      console.error("Failed to load documents:", err);
      set({ loading: false });
    }
  },

  addDocument: (doc: DocMeta) => {
    set((state) => ({
      docs: [doc, ...state.docs],
      recentDoc: doc
    }));
  },

  setSortBy: (sortBy) => set({ sortBy }),
  setFilterKind: (filterKind) => set({ filterKind })
}));

interface ReaderState {
  activeDoc: DocMeta | null;
  progress: Progress | null;
  prefs: ReadingPrefs;
  setActiveDoc: (doc: DocMeta | null) => void;
  setProgress: (p: Progress) => void;
  setPrefs: (p: Partial<ReadingPrefs>) => void;
}

export const useReaderStore = create<ReaderState>()(
  persist(
    (set) => ({
      activeDoc: null,
      progress: null,
      prefs: {
        theme: "light",
        fontFamily: "serif",
        fontSize: 18,
        lineHeight: 1.6,
        margin: 20,
        justify: false,
        flow: "paginated"
      },
      setActiveDoc: (activeDoc) => set({ activeDoc }),
      setProgress: (progress) => set({ progress }),
      setPrefs: (newPrefs) =>
        set((state) => ({ prefs: { ...state.prefs, ...newPrefs } }))
    }),
    {
      name: "folio-reader-prefs"
    }
  )
);
