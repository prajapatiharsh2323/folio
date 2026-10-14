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

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

/**
 * Builds an extract snippet of ~80 characters either side of the first hit and wraps matches in <mark>.
 */
export function buildSnippet(fullText: string, terms: string[]): string {
  if (!fullText) return "";
  const normText = fullText.toLowerCase();

  const validTerms = Array.from(
    new Set(terms.map((t) => t.trim()).filter((t) => t.length > 0))
  );

  let firstIndex = -1;
  let matchedTerm = "";

  for (const term of validTerms) {
    const idx = normText.indexOf(term.toLowerCase());
    if (idx !== -1 && (firstIndex === -1 || idx < firstIndex)) {
      firstIndex = idx;
      matchedTerm = term;
    }
  }

  if (firstIndex === -1) {
    return escapeHtml(fullText.substring(0, 160)) + (fullText.length > 160 ? "…" : "");
  }

  const start = Math.max(0, firstIndex - 80);
  const end = Math.min(fullText.length, firstIndex + matchedTerm.length + 80);
  const rawSnippet = fullText.substring(start, end);

  // Match all terms on the raw snippet before escaping, sorting longer terms first
  const sortedTerms = [...validTerms].sort((a, b) => b.length - a.length);
  const intervals: { start: number; end: number }[] = [];

  if (sortedTerms.length > 0) {
    const combinedRegex = new RegExp(
      sortedTerms.map((t) => t.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")).join("|"),
      "gi"
    );
    let match: RegExpExecArray | null;
    while ((match = combinedRegex.exec(rawSnippet)) !== null) {
      intervals.push({ start: match.index, end: match.index + match[0].length });
    }
  }

  // Merge overlapping or adjacent intervals
  intervals.sort((a, b) => a.start - b.start);
  const merged: { start: number; end: number }[] = [];
  for (const int of intervals) {
    if (merged.length === 0) {
      merged.push({ ...int });
    } else {
      const last = merged[merged.length - 1]!;
      if (int.start <= last.end) {
        last.end = Math.max(last.end, int.end);
      } else {
        merged.push({ ...int });
      }
    }
  }

  // Assemble snippet escaping text and highlighting merged matches
  let snippet = "";
  let currIdx = 0;
  for (const int of merged) {
    if (int.start > currIdx) {
      snippet += escapeHtml(rawSnippet.substring(currIdx, int.start));
    }
    snippet += `<mark>${escapeHtml(rawSnippet.substring(int.start, int.end))}</mark>`;
    currIdx = int.end;
  }
  if (currIdx < rawSnippet.length) {
    snippet += escapeHtml(rawSnippet.substring(currIdx));
  }

  if (start > 0) snippet = "…" + snippet;
  if (end < fullText.length) snippet = snippet + "…";

  return snippet;
}

/**
 * SearchIndex wraps MiniSearch conforming to Section 7 indexing and query options.
 */
export class SearchIndex {
  private miniSearch: MiniSearch<SearchDocEntry>;
  private docChaptersMap: Map<string, string[]> = new Map();
  private rawTextsMap: Map<string, string> = new Map();

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
    const chapters = this.docChaptersMap.get(entry.docId) || [];
    if (!chapters.includes(entry.chapterId)) {
      chapters.push(entry.chapterId);
      this.docChaptersMap.set(entry.docId, chapters);
    }
    if (entry.text) {
      this.rawTextsMap.set(entry.id, entry.text);
    }
    if (this.miniSearch.has(entry.id)) {
      try {
        this.miniSearch.discard(entry.id);
      } catch {
        // safe ignore
      }
    }
    try {
      this.miniSearch.add(entry);
    } catch {
      try {
        this.miniSearch.discard(entry.id);
        this.miniSearch.add(entry);
      } catch {
        // safe fallback
      }
    }
  }

  addAll(entries: SearchDocEntry[]): void {
    // Deduplicate entries by ID within the incoming batch
    const uniqueMap = new Map<string, SearchDocEntry>();
    for (const entry of entries) {
      uniqueMap.set(entry.id, entry);
    }
    for (const entry of uniqueMap.values()) {
      this.add(entry);
    }
  }

  removeByDocId(docId: string): void {
    const chapters = this.docChaptersMap.get(docId) || [];
    for (const ch of chapters) {
      const id = `${docId}:${ch}`;
      this.rawTextsMap.delete(id);
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

      const rawText = rawTexts?.get(`${docId}:${chapterId}`) || this.rawTextsMap.get(`${docId}:${chapterId}`) || "";
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
