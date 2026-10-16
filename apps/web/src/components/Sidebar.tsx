import React from "react";
import { NavLink } from "react-router-dom";
import { BookOpen, Search, Bookmark, Settings, Plus } from "lucide-react";

export const Sidebar: React.FC = () => {
  const items = [
    { to: "/", label: "Library", icon: BookOpen },
    { to: "/search", label: "Search", icon: Search },
    { to: "/notes", label: "Notes", icon: Bookmark },
    { to: "/settings", label: "Settings", icon: Settings }
  ];

  return (
    <aside className="hidden md:flex flex-col w-64 h-full bg-surface border-r border-border p-4 ui-chrome select-none">
      <div className="flex items-center gap-3 px-3 py-4 mb-6">
        <div className="w-9 h-9 rounded-xl bg-surface border border-border/60 p-1 flex items-center justify-center shadow-xs overflow-hidden shrink-0">
          <img
            src="/icons/logo.png"
            alt="Folio Logo"
            className="w-full h-full object-contain"
          />
        </div>
        <div>
          <h1 className="text-lg font-bold tracking-tight text-text">Folio</h1>
          <p className="text-xs text-muted">Reading Library</p>
        </div>
      </div>

      <nav className="flex-1 space-y-1">
        {items.map(({ to, label, icon: Icon }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              `flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-colors ${
                isActive
                  ? "bg-accent/10 text-accent font-semibold"
                  : "text-muted hover:text-text hover:bg-bg"
              }`
            }
          >
            <Icon className="w-4 h-4" />
            <span>{label}</span>
          </NavLink>
        ))}
      </nav>

      <div className="pt-4 border-t border-border">
        <NavLink
          to="/import"
          className="flex items-center justify-center gap-2 w-full py-2.5 px-4 rounded-lg bg-accent text-white font-medium text-sm hover:opacity-95 transition-opacity"
        >
          <Plus className="w-4 h-4" />
          <span>Import document</span>
        </NavLink>
      </div>
    </aside>
  );
};
