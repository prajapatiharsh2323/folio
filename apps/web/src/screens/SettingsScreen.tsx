import React, { useEffect, useState } from "react";
import { useSettingsStore, Theme } from "../state/settings-store.js";
import { Moon, Sun, Coffee, Database, ShieldCheck } from "lucide-react";

export const SettingsScreen: React.FC = () => {
  const { theme, setTheme } = useSettingsStore();
  const [persistentStorage, setPersistentStorage] = useState<boolean | null>(null);

  useEffect(() => {
    if (typeof navigator !== "undefined" && navigator.storage?.persisted) {
      navigator.storage.persisted().then(setPersistentStorage).catch(() => setPersistentStorage(false));
    }
  }, []);

  const themes: { id: Theme; name: string; icon: React.FC<{ className?: string }> }[] = [
    { id: "light", name: "Light", icon: Sun },
    { id: "warm", name: "Warm", icon: Coffee },
    { id: "dark", name: "Dark", icon: Moon }
  ];

  return (
    <div className="flex-1 flex flex-col p-4 md:p-8 max-w-3xl mx-auto w-full">
      <header className="mb-6 pb-4 border-b border-border">
        <h2 className="text-2xl font-bold tracking-tight text-text">Settings</h2>
        <p className="text-sm text-muted">Preferences, theme, and offline storage status</p>
      </header>

      {/* Theme selection */}
      <section className="mb-8">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-muted mb-3">Theme</h3>
        <div className="grid grid-cols-3 gap-3">
          {themes.map(({ id, name, icon: Icon }) => (
            <button
              key={id}
              onClick={() => setTheme(id)}
              className={`flex items-center justify-center gap-2.5 p-3 rounded-lg border text-sm font-medium transition-all ${
                theme === id
                  ? "border-accent bg-accent/10 text-accent font-semibold"
                  : "border-border bg-surface text-text hover:border-muted"
              }`}
            >
              <Icon className="w-4 h-4" />
              <span>{name}</span>
            </button>
          ))}
        </div>
      </section>

      {/* Storage and persistence info */}
      <section className="mb-8 p-4 rounded-card border border-border bg-surface space-y-3">
        <div className="flex items-center gap-2 text-sm font-semibold text-text">
          <Database className="w-4 h-4 text-accent" />
          <span>Origin-Private File System (OPFS)</span>
        </div>
        <p className="text-xs text-muted">
          Folio stores all your documents locally on this device in the private file system. Files never leave your device.
        </p>
        <div className="flex items-center gap-2 text-xs pt-2 border-t border-border/50 text-muted">
          <ShieldCheck className="w-3.5 h-3.5 text-accent" />
          <span>
            Storage Persistence:{" "}
            <strong className="text-text">
              {persistentStorage === null
                ? "Checking..."
                : persistentStorage
                ? "Granted (protected from eviction)"
                : "Standard"}
            </strong>
          </span>
        </div>
      </section>

      {/* About & Version */}
      <section className="text-xs text-muted space-y-1">
        <p>Folio v0.1.0 · Phase 0 Scaffold</p>
        <p>Local-first reading library for articles and books.</p>
      </section>
    </div>
  );
};
