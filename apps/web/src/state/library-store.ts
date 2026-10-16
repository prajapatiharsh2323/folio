import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { DocMeta, Progress, ReadingPrefs, FileStore } from "@folio/core";
import { OpfsStore, MemoryStore, LibraryManager, FsAccessStore } from "@folio/core";
import { useSettingsStore, applyThemeToDom, type Theme } from "./settings-store.js";

// Storage provider for web
const isOpfsSupported = typeof navigator !== "undefined" && !!navigator.storage?.getDirectory;
export let fileStore: FileStore = isOpfsSupported ? new OpfsStore() : new MemoryStore();
export let libraryManager = new LibraryManager(fileStore);

export function setCustomFileStore(newStore: FileStore): void {
  fileStore = newStore;
  libraryManager = new LibraryManager(newStore);
}

async function reindexAllDocs(): Promise<void> {
  try {
    await libraryManager.rebuildSearchIndex();
    const { searchClient } = await import("../search/search-client.js");
    await searchClient.reloadIndex();
  } catch {
    // ignore
  }
}

// Restore saved directory handle if previously connected in folder mode
if (typeof window !== "undefined") {
  FsAccessStore.restoreSaved()
    .then(async (savedStore) => {
      if (savedStore) {
        setCustomFileStore(savedStore);
        await useLibraryStore.getState().loadLibrary();
        await reindexAllDocs();
      } else {
        const status = await FsAccessStore.getSavedHandleStatus();
        if (status === "prompt") {
          useLibraryStore.setState({ folderNeedsPermission: true });
        }
      }
    })
    .catch(() => {});
}

interface LibraryState {
  docs: DocMeta[];
  recentDoc: DocMeta | null;
  loading: boolean;
  folderNeedsPermission: boolean;
  sortBy: "recent" | "title" | "author" | "progress";
  filterKind: "all" | "book" | "article";
  loadLibrary: () => Promise<void>;
  addDocument: (doc: DocMeta) => void;
  reconnectFolder: () => Promise<boolean>;
  setSortBy: (sort: "recent" | "title" | "author" | "progress") => void;
  setFilterKind: (kind: "all" | "book" | "article") => void;
}

export const useLibraryStore = create<LibraryState>((set, get) => ({
  docs: [],
  recentDoc: null,
  loading: false,
  folderNeedsPermission: false,
  sortBy: "recent",
  filterKind: "all",

  loadLibrary: async () => {
    set({ loading: true });
    try {
      const docs = await libraryManager.listDocuments();
      let recent: DocMeta | null = docs.length > 0 ? docs[0]! : null;

      // Select most recently read document from user progress (Finding 19)
      try {
        const allProgress = await libraryManager.getAllProgress();
        let latestTime = 0;
        for (const [docId, p] of Object.entries(allProgress)) {
          const t = new Date(p.updatedAt).getTime();
          if (t > latestTime) {
            const match = docs.find((d) => d.id === docId);
            if (match) {
              latestTime = t;
              recent = match;
            }
          }
        }
      } catch {
        // Fallback to docs[0]
      }

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

  reconnectFolder: async () => {
    const store = await FsAccessStore.requestSavedPermission();
    if (store) {
      setCustomFileStore(store);
      set({ folderNeedsPermission: false });
      await get().loadLibrary();
      await reindexAllDocs();
      return true;
    }
    return false;
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

export const DEFAULT_READING_PREFS: ReadingPrefs = {
  theme: "light",
  fontFamily: "serif",
  fontSize: 18,
  lineHeight: 1.6,
  margin: 20,
  justify: false,
  hyphenate: false,
  paragraphSpacing: 0,
  letterSpacing: 0,
  maxWidth: 720,
  brightness: 100,
  flow: "paginated",
  spread: "none",
  pdfZoom: "fit-width"
};

export const useReaderStore = create<ReaderState>()(
  persist(
    (set) => ({
      activeDoc: null,
      progress: null,
      prefs: {
        ...DEFAULT_READING_PREFS,
        theme: (useSettingsStore.getState().theme as Theme) || "light"
      },
      setActiveDoc: (activeDoc) => set({ activeDoc }),
      setProgress: (progress) => set({ progress }),
      setPrefs: (newPrefs) => {
        if (newPrefs.theme) {
          applyThemeToDom(newPrefs.theme as Theme);
          useSettingsStore.getState().setTheme(newPrefs.theme as Theme);
        }
        set((state) => ({ prefs: { ...state.prefs, ...newPrefs } }));
      }
    }),
    {
      name: "folio-reader-prefs",
      version: 2,
      migrate: (persistedState: unknown, _version: number) => {
        return persistedState as ReaderState;
      },
      merge: (persistedState, currentState) => ({
        ...currentState,
        ...(typeof persistedState === "object" && persistedState !== null ? persistedState : {}),
        prefs: {
          ...DEFAULT_READING_PREFS,
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          ...((persistedState as any)?.prefs || {})
        }
      })
    }
  )
);

// Listen for global theme changes from SettingsScreen or other screens
useSettingsStore.subscribe((settings) => {
  const currentReaderTheme = useReaderStore.getState().prefs.theme;
  if (currentReaderTheme !== settings.theme) {
    useReaderStore.setState((state) => ({
      prefs: { ...state.prefs, theme: settings.theme }
    }));
  }
});
