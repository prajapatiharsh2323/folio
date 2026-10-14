import type { GroupedSearchResult, SearchDocEntry } from "@folio/core";
import { SearchIndex } from "@folio/core";
import { libraryManager } from "../state/library-store.js";
import type { WorkerMessageIn, WorkerMessageOut } from "./search.worker.js";

class SearchClient {
  private worker: Worker | null = null;
  private pendingRequests = new Map<string, (results: GroupedSearchResult[]) => void>();
  private initPromise: Promise<void> | null = null;
  private rawTextsCache = new Map<string, string>(); // `${docId}:${chapterId}` -> text

  private initWorker(): Promise<void> {
    if (this.initPromise) return this.initPromise;

    this.initPromise = (async () => {
      try {
        this.worker = new Worker(new URL("./search.worker.ts", import.meta.url), {
          type: "module"
        });

        // Try to load persisted index from OPFS first
        let jsonIndex: string | undefined;
        try {
          const loadedIndex = await libraryManager.getSearchIndex();
          jsonIndex = loadedIndex.toJSON();
        } catch {
          // If no index exists, worker starts empty
        }

        await new Promise<void>((resolve) => {
          if (!this.worker) {
            resolve();
            return;
          }

          this.worker.onmessage = (e: MessageEvent<WorkerMessageOut>) => {
            const msg = e.data;
            if (msg.type === "READY") {
              resolve();
            } else if (msg.type === "SEARCH_RESULTS") {
              const resolver = this.pendingRequests.get(msg.id);
              if (resolver) {
                resolver(msg.results);
                this.pendingRequests.delete(msg.id);
              }
            } else if (msg.type === "INDEXED" || msg.type === "REMOVED") {
              // Persist updated index to OPFS asynchronously
              if (msg.jsonIndex) {
                const loaded = SearchIndex.loadJSON(msg.jsonIndex);
                libraryManager.saveSearchIndex(loaded).catch(console.error);
              }
            }
          };

          const initMsg: WorkerMessageIn = { type: "INIT", jsonIndex };
          this.worker.postMessage(initMsg);
        });
      } catch (err) {
        console.error("Failed to start search worker:", err);
      }
    })();

    return this.initPromise;
  }

  async loadRawTextsForDoc(docId: string): Promise<void> {
    try {
      const searchTexts = await libraryManager.getSearchText(docId);
      for (const st of searchTexts) {
        this.rawTextsCache.set(`${docId}:${st.chapterId}`, st.text);
      }
    } catch {
      // ignore
    }
  }

  async search(query: string): Promise<GroupedSearchResult[]> {
    await this.initWorker();
    if (!this.worker || !query.trim()) return [];

    const reqId = crypto.randomUUID();

    // Warm raw text snippet cache
    const rawTextsObj: Record<string, string> = {};
    for (const [k, v] of this.rawTextsCache.entries()) {
      rawTextsObj[k] = v;
    }

    return new Promise((resolve) => {
      this.pendingRequests.set(reqId, resolve);
      const msg: WorkerMessageIn = {
        type: "SEARCH",
        id: reqId,
        query,
        rawTexts: rawTextsObj
      };
      this.worker?.postMessage(msg);
    });
  }

  async indexDocs(entries: SearchDocEntry[]): Promise<void> {
    await this.initWorker();
    for (const e of entries) {
      this.rawTextsCache.set(`${e.docId}:${e.chapterId}`, e.text);
    }
    const msg: WorkerMessageIn = { type: "INDEX_DOCS", entries };
    this.worker?.postMessage(msg);
  }

  async removeDoc(docId: string): Promise<void> {
    await this.initWorker();
    const msg: WorkerMessageIn = { type: "REMOVE_DOC", docId };
    this.worker?.postMessage(msg);
  }

  async reloadIndex(): Promise<void> {
    await this.initWorker();
    this.rawTextsCache.clear();
    let jsonIndex: string | undefined;
    try {
      const loadedIndex = await libraryManager.getSearchIndex();
      jsonIndex = loadedIndex.toJSON();
    } catch {
      // If no index exists, worker starts fresh
    }
    this.worker?.postMessage({ type: "INIT", jsonIndex });
  }
}

export const searchClient = new SearchClient();
