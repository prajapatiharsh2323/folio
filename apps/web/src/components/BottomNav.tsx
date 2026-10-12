import React from "react";
import { NavLink } from "react-router-dom";
import { BookOpen, Search, Bookmark, Settings } from "lucide-react";

export const BottomNav: React.FC = () => {
  const items = [
    { to: "/", label: "Library", icon: BookOpen },
    { to: "/search", label: "Search", icon: Search },
    { to: "/notes", label: "Notes", icon: Bookmark },
    { to: "/settings", label: "Settings", icon: Settings }
  ];

  return (
    <nav className="md:hidden fixed bottom-0 left-0 right-0 h-16 bg-surface border-t border-border flex items-center justify-around px-2 z-50 ui-chrome pb-[env(safe-area-inset-bottom)]">
      {items.map(({ to, label, icon: Icon }) => (
        <NavLink
          key={to}
          to={to}
          className={({ isActive }) =>
            `flex flex-col items-center justify-center w-16 h-12 rounded-lg text-xs transition-colors ${
              isActive ? "text-accent font-semibold" : "text-muted hover:text-text"
            }`
          }
        >
          <Icon className="w-5 h-5 mb-1" />
          <span>{label}</span>
        </NavLink>
      ))}
    </nav>
  );
};
