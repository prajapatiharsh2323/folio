import React, { useState } from "react";
import { Search, History } from "lucide-react";

export const SearchScreen: React.FC = () => {
  const [query, setQuery] = useState("");

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-3xl mx-auto w-full">
      <header className="mb-6">
        <h2 className="text-2xl font-bold tracking-tight text-text">Search</h2>
        <p className="text-sm text-muted">Search titles, authors, tags, and full text offline</p>
      </header>

      <div className="relative mb-6">
        <Search className="absolute left-3.5 top-3.5 w-4 h-4 text-muted" />
        <input
          type="search"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search library in English or Hindi..."
          className="w-full pl-10 pr-4 py-2.5 rounded-lg border border-border bg-surface text-text placeholder:text-muted focus:outline-none focus:border-accent"
        />
      </div>

      <div className="flex items-center gap-2 text-xs font-semibold uppercase tracking-wider text-muted mb-4">
        <History className="w-3.5 h-3.5" />
        <span>Recent Searches</span>
      </div>
      <p className="text-sm text-muted">No recent searches yet.</p>
    </div>
  );
};
