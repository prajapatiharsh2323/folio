import MiniSearch from "minisearch";
import { normalise, tokenise } from "./normalise.js";

export interface SearchDocEntry {
  id: string; // docId:chapterId
  docId: string;
  chapterId: string;
  title: string;
  chapterTitle: string;
  author: string;
  tags: string[];
  text: string;
}

export interface SearchHit {
  docId: string;
  chapterId: string;
  title: string;
  chapterTitle: string;
  author: string;
  score: number;
  snippet: string;
  matchTerms: string[];
}

export interface GroupedSearchResult {
  docId: string;
  title: string;
  author: string;
  hits: {
    chapterId: string;
    chapterTitle: string;
    score: number;
    snippet: string;
  }[];
}

/**
 * Builds an extract snippet of ~80 characters either side of the first hit and wraps matches in <mark>.
 */
export function buildSnippet(fullText: string, terms: string[]): string {
  if (!fullText) return "";
  const normText = fullText.toLowerCase();

  let firstIndex = -1;
  let matchedTerm = "";

  for (const term of terms) {
    const idx = normText.indexOf(term.toLowerCase());
    if (idx !== -1 && (firstIndex === -1 || idx < firstIndex)) {
      firstIndex = idx;
      matchedTerm = term;
    }
  }

  if (firstIndex === -1) {
    return fullText.substring(0, 160) + (fullText.length > 160 ? "…" : "");
  }

  const start = Math.max(0, firstIndex - 80);
  const end = Math.min(fullText.length, firstIndex + matchedTerm.length + 80);
  let snippet = fullText.substring(start, end);

  if (start > 0) snippet = "…" + snippet;
  if (end < fullText.length) snippet = snippet + "…";

  // Wrap matched terms in <mark>
  for (const term of terms) {
    const escaped = term.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    snippet = snippet.replace(new RegExp(`(${escaped})`, "gi"), "<mark>$1</mark>");
  }

  return snippet;
}

/**
 * SearchIndex wraps MiniSearch conforming to Section 7 indexing and query options.
 */
export class SearchIndex {
  private miniSearch: MiniSearch<SearchDocEntry>;
  private docChaptersMap: Map<string, string[]> = new Map();

  constructor() {
    this.miniSearch = new MiniSearch<SearchDocEntry>({
      fields: ["title", "author", "tags", "text"],
      storeFields: ["docId", "chapterId", "title", "chapterTitle", "author"],
      processTerm: (term) => normalise(term),
      tokenize: (text) => tokenise(text),
      searchOptions: {
        boost: { title: 4, author: 3, tags: 3, text: 1 },
        prefix: true,
        combineWith: "AND",
        fuzzy: (term) => {
          // Section 7: fuzzy 0.2 ONLY for Latin terms longer than 4 characters
          const isLatin = /^[a-z]+$/i.test(term);
          if (isLatin && term.length > 4) {
            return 0.2;
          }
          return false;
        }
      }
    });
  }

  add(entry: SearchDocEntry): void {
    // If id already indexed, discard first
    if (this.miniSearch.has(entry.id)) {
      this.miniSearch.discard(entry.id);
    }
    const chapters = this.docChaptersMap.get(entry.docId) || [];
    if (!chapters.includes(entry.chapterId)) {
      chapters.push(entry.chapterId);
      this.docChaptersMap.set(entry.docId, chapters);
    }
    this.miniSearch.add(entry);
  }

  addAll(entries: SearchDocEntry[]): void {
    for (const entry of entries) {
      const chapters = this.docChaptersMap.get(entry.docId) || [];
      if (!chapters.includes(entry.chapterId)) {
        chapters.push(entry.chapterId);
        this.docChaptersMap.set(entry.docId, chapters);
      }
    }
    this.miniSearch.addAll(entries);
  }

  removeByDocId(docId: string): void {
    const chapters = this.docChaptersMap.get(docId) || [];
    for (const ch of chapters) {
      const id = `${docId}:${ch}`;
      if (this.miniSearch.has(id)) {
        this.miniSearch.discard(id);
      }
    }
    this.docChaptersMap.delete(docId);
  }

  search(query: string, rawTexts?: Map<string, string>): GroupedSearchResult[] {
    if (!query.trim()) return [];

    const rawHits = this.miniSearch.search(query);
    const queryTokens = tokenise(query);

    // Group hits by document
    const docMap = new Map<string, GroupedSearchResult>();

    for (const hit of rawHits) {
      const docId = hit.docId as string;
      const chapterId = hit.chapterId as string;
      const title = hit.title as string;
      const chapterTitle = hit.chapterTitle as string;
      const author = hit.author as string;

      const rawText = rawTexts ? rawTexts.get(`${docId}:${chapterId}`) || "" : "";
      const snippet = buildSnippet(rawText, queryTokens);

      let group = docMap.get(docId);
      if (!group) {
        group = {
          docId,
          title,
          author,
          hits: []
        };
        docMap.set(docId, group);
      }

      group.hits.push({
        chapterId,
        chapterTitle,
        score: hit.score,
        snippet
      });

      // Section 7: return at most 50 documents
      if (docMap.size >= 50) break;
    }

    return Array.from(docMap.values());
  }

  toJSON(): string {
    const serialized = {
      miniSearch: this.miniSearch.toJSON(),
      chapters: Array.from(this.docChaptersMap.entries())
    };
    return JSON.stringify(serialized);
  }

  static loadJSON(jsonStr: string): SearchIndex {
    const index = new SearchIndex();
    try {
      const parsed = JSON.parse(jsonStr);
      const miniData = parsed.miniSearch ? parsed.miniSearch : parsed;
      index.miniSearch = MiniSearch.loadJSON(JSON.stringify(miniData), {
        fields: ["title", "author", "tags", "text"],
        storeFields: ["docId", "chapterId", "title", "chapterTitle", "author"],
        processTerm: (term) => normalise(term),
        tokenize: (text) => tokenise(text),
        searchOptions: {
          boost: { title: 4, author: 3, tags: 3, text: 1 },
          prefix: true,
          combineWith: "AND",
          fuzzy: (term) => {
            const isLatin = /^[a-z]+$/i.test(term);
            if (isLatin && term.length > 4) {
              return 0.2;
            }
            return false;
          }
        }
      });
      if (parsed.chapters && Array.isArray(parsed.chapters)) {
        index.docChaptersMap = new Map(parsed.chapters);
      }
    } catch (err) {
      console.error("Failed to load search index JSON:", err);
    }
    return index;
  }
}
