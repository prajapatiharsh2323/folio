import { SearchIndex, type SearchDocEntry, type GroupedSearchResult } from "@folio/core";

let searchIndex = new SearchIndex();

export type WorkerMessageIn =
  | { type: "INIT"; jsonIndex?: string }
  | { type: "INDEX_DOCS"; entries: SearchDocEntry[] }
  | { type: "REMOVE_DOC"; docId: string }
  | { type: "SEARCH"; id: string; query: string; rawTexts?: Record<string, string> };

export type WorkerMessageOut =
  | { type: "READY" }
  | { type: "INDEXED"; count: number; jsonIndex: string }
  | { type: "REMOVED"; docId: string; jsonIndex: string }
  | { type: "SEARCH_RESULTS"; id: string; results: GroupedSearchResult[] };

self.onmessage = (e: MessageEvent<WorkerMessageIn>) => {
  const msg = e.data;
  if (!msg) return;

  switch (msg.type) {
    case "INIT": {
      if (msg.jsonIndex) {
        searchIndex = SearchIndex.loadJSON(msg.jsonIndex);
      } else {
        searchIndex = new SearchIndex();
      }
      self.postMessage({ type: "READY" });
      break;
    }

    case "INDEX_DOCS": {
      try {
        searchIndex.addAll(msg.entries);
        const jsonIndex = searchIndex.toJSON();
        self.postMessage({ type: "INDEXED", count: msg.entries.length, jsonIndex });
      } catch (err) {
        console.warn("Failed to index docs in worker:", err);
      }
      break;
    }

    case "REMOVE_DOC": {
      searchIndex.removeByDocId(msg.docId);
      const jsonIndex = searchIndex.toJSON();
      self.postMessage({ type: "REMOVED", docId: msg.docId, jsonIndex });
      break;
    }

    case "SEARCH": {
      const rawTextMap = msg.rawTexts
        ? new Map(Object.entries(msg.rawTexts))
        : undefined;
      const results = searchIndex.search(msg.query, rawTextMap);
      self.postMessage({ type: "SEARCH_RESULTS", id: msg.id, results });
      break;
    }
  }
};
