import React, { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { Search, History, BookOpen, Clock, X, ChevronRight } from "lucide-react";
import type { GroupedSearchResult } from "@folio/core";
import { searchClient } from "../search/search-client.js";
import { useLibraryStore } from "../state/library-store.js";

const RECENT_SEARCHES_KEY = "folio-recent-searches";

export const SearchScreen: React.FC = () => {
  const navigate = useNavigate();
  const { docs, loadLibrary } = useLibraryStore();
  const [query, setQuery] = useState("");
  const [results, setResults] = useState<GroupedSearchResult[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [recentSearches, setRecentSearches] = useState<string[]>([]);
  const debounceTimerRef = useRef<number | null>(null);

  useEffect(() => {
    loadLibrary();
    try {
      const stored = localStorage.getItem(RECENT_SEARCHES_KEY);
      if (stored) {
        setRecentSearches(JSON.parse(stored));
      }
    } catch {
      // ignore
    }
  }, [loadLibrary]);

  // Load raw texts cache into searchClient for all docs so snippets can be generated
  useEffect(() => {
    for (const d of docs) {
      searchClient.loadRawTextsForDoc(d.id).catch(console.error);
    }
  }, [docs]);

  // 120 ms debounce as specified in Section 7
  useEffect(() => {
    if (debounceTimerRef.current) {
      window.clearTimeout(debounceTimerRef.current);
    }

    const trimmed = query.trim();
    if (!trimmed) {
      setResults([]);
      setIsSearching(false);
      return;
    }

    setIsSearching(true);
    debounceTimerRef.current = window.setTimeout(async () => {
      try {
        const hits = await searchClient.search(trimmed);
        setResults(hits);

        // Save to recent searches if length >= 2
        if (trimmed.length >= 2) {
          setRecentSearches((prev) => {
            const updated = [trimmed, ...prev.filter((q) => q !== trimmed)].slice(0, 8);
            try {
              localStorage.setItem(RECENT_SEARCHES_KEY, JSON.stringify(updated));
            } catch {
              // ignore
            }
            return updated;
          });
        }
      } catch (err) {
        console.error("Search error:", err);
      } finally {
        setIsSearching(false);
      }
    }, 120);

    return () => {
      if (debounceTimerRef.current) {
        window.clearTimeout(debounceTimerRef.current);
      }
    };
  }, [query]);

  const handleClearRecent = () => {
    setRecentSearches([]);
    localStorage.removeItem(RECENT_SEARCHES_KEY);
  };

  const handleSelectRecent = (term: string) => {
    setQuery(term);
  };

  const handleOpenHit = (docId: string, chapterId: string) => {
    const doc = docs.find((d) => d.id === docId);
    if (!doc) return;
    navigate(`/read/${doc.slug}?chapter=${encodeURIComponent(chapterId)}&q=${encodeURIComponent(query)}`);
  };

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-4xl mx-auto w-full">
      <header className="mb-6">
        <h2 className="text-2xl font-bold tracking-tight text-text">Search Library</h2>
        <p className="text-sm text-muted">
          Offline full-text search across titles, authors, tags, and chapters in English and Hindi
        </p>
      </header>

      {/* Search Input Bar */}
      <div className="relative mb-6">
        <Search className="absolute left-3.5 top-3.5 w-4 h-4 text-muted pointer-events-none" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search titles, authors, text (e.g. 'knowledge', 'गंगा', 'विद्यार्थी')..."
          className="w-full pl-10 pr-10 py-3 rounded-xl border border-border bg-surface text-text placeholder:text-muted focus:outline-none focus:border-accent text-sm shadow-sm"
          autoFocus
        />
        {query && (
          <button
            onClick={() => setQuery("")}
            className="absolute right-3.5 top-3.5 text-muted hover:text-text p-0.5 rounded"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {/* Recent Searches */}
      {!query && recentSearches.length > 0 && (
        <div className="mb-8">
          <div className="flex items-center justify-between mb-3 text-xs font-semibold uppercase tracking-wider text-muted">
            <div className="flex items-center gap-1.5">
              <History className="w-3.5 h-3.5" />
              <span>Recent Searches</span>
            </div>
            <button
              onClick={handleClearRecent}
              className="text-xs text-muted hover:text-text lowercase font-normal"
            >
              Clear
            </button>
          </div>
          <div className="flex flex-wrap gap-2">
            {recentSearches.map((term) => (
              <button
                key={term}
                onClick={() => handleSelectRecent(term)}
                className="px-3 py-1.5 rounded-lg border border-border bg-surface hover:border-accent text-xs font-medium text-text transition-colors flex items-center gap-1.5"
              >
                <Clock className="w-3 h-3 text-muted" />
                <span>{term}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* Loading state indicator */}
      {isSearching && (
        <div className="py-2 text-xs text-muted flex items-center gap-2">
          <div className="w-3 h-3 rounded-full border-2 border-accent border-t-transparent animate-spin" />
          <span>Searching index...</span>
        </div>
      )}

      {/* Search Results */}
      {query && results.length > 0 && (
        <div className="space-y-6">
          <p className="text-xs text-muted font-medium">
            Found hits in {results.length} {results.length === 1 ? "document" : "documents"}
          </p>
          {results.map((group) => {
            const matchedDoc = docs.find((d) => d.id === group.docId);
            return (
              <div
                key={group.docId}
                className="rounded-xl border border-border bg-surface p-4 shadow-sm space-y-3"
              >
                <div className="flex items-start justify-between border-b border-border/50 pb-2">
                  <div className="flex items-center gap-2.5">
                    <div className="w-8 h-10 rounded bg-accent/15 text-accent flex items-center justify-center font-bold flex-shrink-0">
                      <BookOpen className="w-4 h-4" />
                    </div>
                    <div>
                      <h4 className="font-semibold text-text text-sm">{group.title}</h4>
                      <p className="text-xs text-muted">{group.author || "Unknown author"}</p>
                    </div>
                  </div>
                  <span className="text-[11px] font-mono text-muted uppercase bg-bg px-2 py-0.5 rounded">
                    {matchedDoc?.sourceFormat || "doc"}
                  </span>
                </div>

                {/* Hits in this document */}
                <div className="space-y-2">
                  {group.hits.map((hit, idx) => (
                    <div
                      key={`${group.docId}-${hit.chapterId}-${idx}`}
                      onClick={() => handleOpenHit(group.docId, hit.chapterId)}
                      className="p-2.5 rounded-lg border border-border/60 hover:border-accent hover:bg-bg/40 cursor-pointer transition-all flex items-start justify-between gap-3 group"
                    >
                      <div className="space-y-1 overflow-hidden flex-1">
                        <span className="text-xs font-semibold text-accent block">
                          {hit.chapterTitle || "Chapter"}
                        </span>
                        {hit.snippet ? (
                          <p
                            className="text-xs text-muted leading-relaxed line-clamp-3 [&>mark]:bg-accent/25 [&>mark]:text-text [&>mark]:font-medium [&>mark]:rounded-sm [&>mark]:px-0.5"
                            dangerouslySetInnerHTML={{ __html: hit.snippet }}
                          />
                        ) : (
                          <p className="text-xs text-muted italic">Match in title or metadata</p>
                        )}
                      </div>
                      <ChevronRight className="w-4 h-4 text-muted group-hover:text-accent flex-shrink-0 mt-1 transition-colors" />
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* Empty State */}
      {query && !isSearching && results.length === 0 && (
        <div className="flex-1 flex flex-col items-center justify-center py-16 text-center text-muted">
          <Search className="w-10 h-10 stroke-1 mb-3 text-muted/60" />
          <p className="text-base font-medium text-text">No results for &ldquo;{query}&rdquo;</p>
          <p className="text-xs max-w-sm mt-1">
            Check the spelling or try searching for keywords in English or Hindi.
          </p>
        </div>
      )}
    </div>
  );
};
