import React, { useState, useEffect, useRef, useCallback } from "react";
import { Drawer } from "vaul";
import {
  Sun,
  Moon,
  Coffee,
  Laptop,
  AlignLeft,
  AlignJustify,
  Columns2,
  BookOpen,
  ScrollText,
  RotateCcw,
  X,
  Sparkles,
  RectangleVertical
} from "lucide-react";
import "../reader/fonts.js";
import { useReaderStore, DEFAULT_READING_PREFS } from "../state/library-store.js";
import type { ReadingPrefs } from "@folio/core";

interface TypographySheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

interface ReadingPreset {
  id: string;
  label: string;
  desc: string;
  prefs: Partial<ReadingPrefs>;
}

const PRESETS: ReadingPreset[] = [
  {
    id: "comfortable",
    label: "Comfortable",
    desc: "Literata · Relaxed spacing",
    prefs: {
      fontFamily: "serif",
      fontSize: 18,
      lineHeight: 1.65,
      margin: 24,
      maxWidth: 720,
      paragraphSpacing: 0.6,
      letterSpacing: 0,
      justify: false,
      hyphenate: false,
      flow: "paginated"
    }
  },
  {
    id: "compact",
    label: "Compact",
    desc: "System Sans · High density",
    prefs: {
      fontFamily: "sans",
      fontSize: 16,
      lineHeight: 1.45,
      margin: 16,
      maxWidth: 680,
      paragraphSpacing: 0.3,
      letterSpacing: 0,
      justify: false,
      hyphenate: false,
      flow: "paginated"
    }
  },
  {
    id: "continuous",
    label: "Long Scroll",
    desc: "Infinite vertical stream",
    prefs: {
      fontFamily: "serif",
      fontSize: 18,
      lineHeight: 1.7,
      margin: 24,
      maxWidth: 720,
      paragraphSpacing: 0.8,
      letterSpacing: 0,
      justify: false,
      hyphenate: false,
      flow: "scrolled"
    }
  },
  {
    id: "focus",
    label: "Academic Focus",
    desc: "Compact · Narrow column",
    prefs: {
      fontFamily: "serif",
      fontSize: 16,
      lineHeight: 1.5,
      margin: 36,
      maxWidth: 620,
      paragraphSpacing: 0.5,
      letterSpacing: 0,
      justify: true,
      hyphenate: true,
      flow: "paginated"
    }
  }
];

export const TypographySheet: React.FC<TypographySheetProps> = ({ open, onOpenChange }) => {
  const { prefs, setPrefs } = useReaderStore();
  const [localPrefs, setLocalPrefs] = useState<ReadingPrefs>(prefs);
  const [activeTab, setActiveTab] = useState<"style" | "layout">("style");
  const [isDesktop, setIsDesktop] = useState(
    typeof window !== "undefined" ? window.innerWidth >= 768 : false
  );
  const [viewportWidth, setViewportWidth] = useState(
    typeof window !== "undefined" ? window.innerWidth : 1024
  );

  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    setLocalPrefs(prefs);
  }, [prefs]);

  useEffect(() => {
    const handleResize = () => {
      setIsDesktop(window.innerWidth >= 768);
      setViewportWidth(window.innerWidth);
    };
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  // Frame-batched & debounced pref update for sliders to preserve 60fps interaction (R-07)
  const updatePrefDebounced = useCallback(
    (partial: Partial<ReadingPrefs>) => {
      setLocalPrefs((prev) => ({ ...prev, ...partial }));
      if (debounceTimerRef.current) clearTimeout(debounceTimerRef.current);
      debounceTimerRef.current = setTimeout(() => {
        setPrefs(partial);
      }, 120);
    },
    [setPrefs]
  );

  const handleReset = () => {
    const currentTheme = prefs.theme;
    const reset = {
      ...DEFAULT_READING_PREFS,
      theme: currentTheme
    };
    setLocalPrefs(reset);
    setPrefs(reset);
  };

  const renderContent = () => (
    <div className="flex-1 overflow-y-auto px-5 py-4 space-y-5 text-sm">
      {/* Live Preview Strip */}
      <div
        className="p-3.5 rounded-xl border border-border shadow-xs transition-colors"
        style={{
          backgroundColor: "var(--bg)",
          color: "var(--text)"
        }}
      >
        <div className="text-[10px] uppercase font-bold tracking-wider text-muted opacity-80 mb-1.5 flex items-center justify-between">
          <span>Live Preview</span>
          <span className="font-mono text-[11px]">
            {localPrefs.fontSize}px · {localPrefs.lineHeight}x · {localPrefs.flow}
          </span>
        </div>
        <p
          style={{
            fontFamily:
              localPrefs.fontFamily === "serif"
                ? '"Literata", "Noto Serif Devanagari", Georgia, serif'
                : localPrefs.fontFamily === "mono"
                ? "ui-monospace, SFMono-Regular, monospace"
                : localPrefs.fontFamily === "publisher"
                ? "inherit"
                : 'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans Devanagari", sans-serif',
            fontSize: `${Math.min(Math.max(localPrefs.fontSize, 14), 22)}px`,
            lineHeight: localPrefs.lineHeight,
            letterSpacing: `${localPrefs.letterSpacing ?? 0}em`,
            textAlign: localPrefs.justify ? "justify" : "left",
            hyphens: localPrefs.hyphenate ? "auto" : "manual"
          }}
          className="transition-all line-clamp-2"
        >
          To read without interruption is to inhabit a quiet room. जलवायु और प्रकृति का संतुलन।
        </p>
      </div>

      {/* Tab Switcher: Theme & Type vs Layout & Page */}
      <div className="grid grid-cols-2 p-1 rounded-xl bg-bg border border-border text-xs font-semibold">
        <button
          onClick={() => setActiveTab("style")}
          className={`py-2 min-h-[40px] rounded-lg transition-all ${
            activeTab === "style"
              ? "bg-surface text-accent shadow-xs"
              : "text-muted hover:text-text"
          }`}
        >
          Theme & Typography
        </button>
        <button
          onClick={() => setActiveTab("layout")}
          className={`py-2 min-h-[40px] rounded-lg transition-all ${
            activeTab === "layout"
              ? "bg-surface text-accent shadow-xs"
              : "text-muted hover:text-text"
          }`}
        >
          Page & Layout
        </button>
      </div>

      {activeTab === "style" ? (
        <>
          {/* Quick Presets */}
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-muted flex items-center gap-1.5 mb-2">
              <Sparkles className="w-3.5 h-3.5 text-accent" />
              <span>Reading Presets</span>
            </span>
            <div className="grid grid-cols-2 gap-2">
              {PRESETS.map((preset) => {
                const isActive = Object.entries(preset.prefs).every(
                  ([k, v]) => localPrefs[k as keyof ReadingPrefs] === v
                );
                return (
                  <button
                    key={preset.id}
                    onClick={() => {
                      setLocalPrefs((prev) => ({ ...prev, ...preset.prefs }));
                      setPrefs(preset.prefs);
                    }}
                    className={`p-2.5 rounded-xl border text-left transition-all min-h-[52px] ${
                      isActive
                        ? "border-accent bg-accent/10 ring-1 ring-accent"
                        : "border-border bg-bg hover:border-accent"
                    }`}
                  >
                    <div className="text-xs font-semibold text-text flex items-center justify-between">
                      <span>{preset.label}</span>
                      {isActive && <span className="w-1.5 h-1.5 rounded-full bg-accent" />}
                    </div>
                    <div className="text-[10px] text-muted truncate mt-0.5">{preset.desc}</div>
                  </button>
                );
              })}
            </div>
          </div>

          {/* 1. Theme Section */}
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
              Color Theme
            </span>
            <div role="radiogroup" aria-label="Theme selection" className="grid grid-cols-4 gap-2">
              {(
                [
                  { id: "light", label: "Light", icon: Sun },
                  { id: "warm", label: "Warm", icon: Coffee },
                  { id: "dark", label: "Dark", icon: Moon },
                  { id: "system", label: "System", icon: Laptop }
                ] as const
              ).map(({ id, label, icon: Icon }) => (
                <button
                  key={id}
                  role="radio"
                  aria-checked={localPrefs.theme === id}
                  onClick={() => {
                    setLocalPrefs((prev) => ({ ...prev, theme: id }));
                    setPrefs({ theme: id });
                  }}
                  className={`flex flex-col items-center justify-center gap-1 py-2 px-2 rounded-lg border text-xs font-medium transition-all min-h-[50px] ${
                    localPrefs.theme === id
                      ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                      : "border-border bg-bg text-text hover:bg-border/30"
                  }`}
                >
                  <Icon className="w-4 h-4" />
                  <span>{label}</span>
                </button>
              ))}
            </div>

            {/* Brightness Slider */}
            <div className="mt-3 pt-3 border-t border-border/60">
              <div className="flex items-center justify-between text-xs text-muted mb-1.5">
                <label htmlFor="brightness-slider" className="font-medium">
                  Screen Dimmer
                </label>
                <span className="font-mono text-xs">{localPrefs.brightness ?? 100}%</span>
              </div>
              <input
                id="brightness-slider"
                type="range"
                min="40"
                max="100"
                step="5"
                value={localPrefs.brightness ?? 100}
                aria-valuetext={`${localPrefs.brightness ?? 100}%`}
                onChange={(e) => updatePrefDebounced({ brightness: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer min-h-[32px]"
              />
            </div>
          </div>

          {/* 2. Typeface Section with Publisher Default (Phase 2) */}
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
              Typeface
            </span>
            <div
              role="radiogroup"
              aria-label="Font family selection"
              className="grid grid-cols-2 sm:grid-cols-4 gap-2"
            >
              <button
                role="radio"
                aria-checked={localPrefs.fontFamily === "serif"}
                onClick={() => {
                  setLocalPrefs((prev) => ({ ...prev, fontFamily: "serif" }));
                  setPrefs({ fontFamily: "serif" });
                }}
                className={`py-2 px-2.5 rounded-lg border text-xs font-serif transition-all min-h-[44px] flex items-center justify-center ${
                  localPrefs.fontFamily === "serif"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                Literata Serif
              </button>
              <button
                role="radio"
                aria-checked={localPrefs.fontFamily === "sans"}
                onClick={() => {
                  setLocalPrefs((prev) => ({ ...prev, fontFamily: "sans" }));
                  setPrefs({ fontFamily: "sans" });
                }}
                className={`py-2 px-2.5 rounded-lg border text-xs font-sans transition-all min-h-[44px] flex items-center justify-center ${
                  localPrefs.fontFamily === "sans"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                System Sans
              </button>
              <button
                role="radio"
                aria-checked={localPrefs.fontFamily === "mono"}
                onClick={() => {
                  setLocalPrefs((prev) => ({ ...prev, fontFamily: "mono" }));
                  setPrefs({ fontFamily: "mono" });
                }}
                className={`py-2 px-2.5 rounded-lg border text-xs font-mono transition-all min-h-[44px] flex items-center justify-center ${
                  localPrefs.fontFamily === "mono"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                Monospace
              </button>
              <button
                role="radio"
                aria-checked={localPrefs.fontFamily === "publisher"}
                onClick={() => {
                  setLocalPrefs((prev) => ({ ...prev, fontFamily: "publisher" }));
                  setPrefs({ fontFamily: "publisher" });
                }}
                className={`py-2 px-2.5 rounded-lg border text-xs transition-all min-h-[44px] flex items-center justify-center ${
                  localPrefs.fontFamily === "publisher"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                Publisher
              </button>
            </div>
          </div>

          {/* 3. Font Size Section */}
          <div>
            <div className="flex items-center justify-between text-xs font-semibold uppercase tracking-wider text-muted mb-2">
              <label htmlFor="font-size-slider">Font Size</label>
              <span className="font-mono">{localPrefs.fontSize}px</span>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={() => {
                  const val = Math.max(14, localPrefs.fontSize - 1);
                  setLocalPrefs((prev) => ({ ...prev, fontSize: val }));
                  setPrefs({ fontSize: val });
                }}
                className="w-11 h-11 border border-border rounded-lg bg-bg text-sm font-bold flex items-center justify-center hover:bg-border/30 min-h-[44px]"
                aria-label="Decrease font size"
              >
                A-
              </button>
              <input
                id="font-size-slider"
                type="range"
                min="14"
                max="32"
                step="1"
                value={localPrefs.fontSize}
                aria-valuetext={`${localPrefs.fontSize} pixels`}
                onChange={(e) => updatePrefDebounced({ fontSize: Number(e.target.value) })}
                className="flex-1 accent-accent cursor-pointer min-h-[32px]"
              />
              <button
                onClick={() => {
                  const val = Math.min(32, localPrefs.fontSize + 1);
                  setLocalPrefs((prev) => ({ ...prev, fontSize: val }));
                  setPrefs({ fontSize: val });
                }}
                className="w-11 h-11 border border-border rounded-lg bg-bg text-sm font-bold flex items-center justify-center hover:bg-border/30 min-h-[44px]"
                aria-label="Increase font size"
              >
                A+
              </button>
            </div>
          </div>
        </>
      ) : (
        <>
          {/* 4. Reading Flow & Spread */}
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
              Reading Mode
            </span>
            <div
              role="radiogroup"
              aria-label="Reading flow selection"
              className="grid grid-cols-2 gap-2"
            >
              <button
                role="radio"
                aria-checked={localPrefs.flow === "paginated"}
                onClick={() => {
                  setLocalPrefs((prev) => ({ ...prev, flow: "paginated" }));
                  setPrefs({ flow: "paginated" });
                }}
                className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-lg border text-xs transition-all min-h-[46px] ${
                  localPrefs.flow === "paginated"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                <BookOpen className="w-4 h-4" />
                <span>Paginated</span>
              </button>
              <button
                role="radio"
                aria-checked={localPrefs.flow === "scrolled"}
                onClick={() => {
                  setLocalPrefs((prev) => ({ ...prev, flow: "scrolled" }));
                  setPrefs({ flow: "scrolled" });
                }}
                className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-lg border text-xs transition-all min-h-[46px] ${
                  localPrefs.flow === "scrolled"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                <ScrollText className="w-4 h-4" />
                <span>Continuous</span>
              </button>
            </div>

            {/* Two-Column Spread on Wide Viewports */}
            {viewportWidth >= 800 && localPrefs.flow === "paginated" && (
              <div className="grid grid-cols-2 gap-2 mt-2">
                <button
                  onClick={() => {
                    setLocalPrefs((prev) => ({ ...prev, spread: "none" }));
                    setPrefs({ spread: "none" });
                  }}
                  className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg border text-xs transition-all min-h-[44px] ${
                    (localPrefs.spread ?? "none") === "none"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  <RectangleVertical className="w-3.5 h-3.5" />
                  <span>Single Column</span>
                </button>
                <button
                  onClick={() => {
                    setLocalPrefs((prev) => ({ ...prev, spread: "auto" }));
                    setPrefs({ spread: "auto" });
                  }}
                  className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg border text-xs transition-all min-h-[44px] ${
                    localPrefs.spread === "auto"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  <Columns2 className="w-3.5 h-3.5" />
                  <span>Two-Page Spread</span>
                </button>
              </div>
            )}
          </div>

          {/* 5. Text Alignment & Hyphenation */}
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
              Alignment & Hyphenation
            </span>
            <div className="grid grid-cols-2 gap-2">
              <button
                onClick={() => {
                  const val = !localPrefs.justify;
                  setLocalPrefs((prev) => ({ ...prev, justify: val }));
                  setPrefs({ justify: val });
                }}
                className={`flex items-center justify-center gap-2 py-2 px-3 rounded-lg border text-xs font-medium transition-all min-h-[44px] ${
                  localPrefs.justify
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                {localPrefs.justify ? (
                  <AlignJustify className="w-4 h-4" />
                ) : (
                  <AlignLeft className="w-4 h-4" />
                )}
                <span>{localPrefs.justify ? "Justified" : "Left Aligned"}</span>
              </button>

              <button
                onClick={() => {
                  const val = !localPrefs.hyphenate;
                  setLocalPrefs((prev) => ({ ...prev, hyphenate: val }));
                  setPrefs({ hyphenate: val });
                }}
                className={`flex items-center justify-center gap-2 py-2 px-3 rounded-lg border text-xs font-medium transition-all min-h-[44px] ${
                  localPrefs.hyphenate
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                <span>Hyphenate: {localPrefs.hyphenate ? "On" : "Off"}</span>
              </button>
            </div>
          </div>

          {/* 6. Spacing & Geometry Sliders */}
          <div className="space-y-4">
            <span className="text-xs font-semibold uppercase tracking-wider text-muted block">
              Spacing & Page Geometry
            </span>

            {/* Line Height */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="line-height-slider">Line Height</label>
                <span className="font-mono">{localPrefs.lineHeight}</span>
              </div>
              <input
                id="line-height-slider"
                type="range"
                min="1.3"
                max="2.4"
                step="0.05"
                value={localPrefs.lineHeight}
                aria-valuetext={`${localPrefs.lineHeight}`}
                onChange={(e) => updatePrefDebounced({ lineHeight: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer min-h-[32px]"
              />
            </div>

            {/* Page Margins */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="margin-slider">Horizontal Margin (Gutters)</label>
                <span className="font-mono">{localPrefs.margin}px</span>
              </div>
              <input
                id="margin-slider"
                type="range"
                min="12"
                max="64"
                step="4"
                value={localPrefs.margin}
                aria-valuetext={`${localPrefs.margin} pixels`}
                onChange={(e) => updatePrefDebounced({ margin: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer min-h-[32px]"
              />
            </div>

            {/* Paragraph Spacing */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="para-spacing-slider">Paragraph Spacing</label>
                <span className="font-mono">{localPrefs.paragraphSpacing ?? 0.5}em</span>
              </div>
              <input
                id="para-spacing-slider"
                type="range"
                min="0"
                max="1.5"
                step="0.1"
                value={localPrefs.paragraphSpacing ?? 0.5}
                aria-valuetext={`${localPrefs.paragraphSpacing ?? 0.5} em`}
                onChange={(e) => updatePrefDebounced({ paragraphSpacing: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer min-h-[32px]"
              />
            </div>

            {/* Letter Spacing */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="letter-spacing-slider">Letter Spacing</label>
                <span className="font-mono">{localPrefs.letterSpacing ?? 0}em</span>
              </div>
              <input
                id="letter-spacing-slider"
                type="range"
                min="-0.02"
                max="0.08"
                step="0.01"
                value={localPrefs.letterSpacing ?? 0}
                aria-valuetext={`${localPrefs.letterSpacing ?? 0} em`}
                onChange={(e) => updatePrefDebounced({ letterSpacing: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer min-h-[32px]"
              />
            </div>

            {/* Reading Width (When viewport >= 768px) */}
            {viewportWidth >= 768 && (
              <div>
                <div className="flex items-center justify-between text-xs text-muted mb-1">
                  <label htmlFor="max-width-slider">Max Reading Column Width</label>
                  <span className="font-mono">{localPrefs.maxWidth ?? 720}px</span>
                </div>
                <input
                  id="max-width-slider"
                  type="range"
                  min="560"
                  max="960"
                  step="20"
                  value={localPrefs.maxWidth ?? 720}
                  aria-valuetext={`${localPrefs.maxWidth ?? 720} pixels`}
                  onChange={(e) => updatePrefDebounced({ maxWidth: Number(e.target.value) })}
                  className="w-full accent-accent cursor-pointer min-h-[32px]"
                />
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );

  if (!open) return null;

  // Non-modal side panel on desktop: Leaves the book text completely visible (R-10)
  if (isDesktop) {
    return (
      <div className="fixed inset-0 z-50 pointer-events-none flex justify-end">
        <aside className="pointer-events-auto relative w-[380px] max-w-[90vw] h-full bg-surface/98 backdrop-blur-md border-l border-border flex flex-col z-10 shadow-2xl animate-in slide-in-from-right duration-200">
          <header className="flex items-center justify-between px-5 py-3.5 border-b border-border">
            <h4 className="font-semibold text-text text-sm">Appearance</h4>
            <div className="flex items-center gap-2">
              <button
                onClick={handleReset}
                title="Reset to defaults"
                className="flex items-center gap-1 text-xs px-2.5 py-1.5 min-h-[36px] rounded-md bg-bg text-muted hover:text-text transition-colors border border-border"
              >
                <RotateCcw className="w-3 h-3" />
                <span>Reset</span>
              </button>
              <button
                onClick={() => onOpenChange(false)}
                className="p-2 min-w-[36px] min-h-[36px] flex items-center justify-center rounded-md hover:bg-bg text-muted hover:text-text transition-colors"
                aria-label="Close"
              >
                <X className="w-4 h-4" />
              </button>
            </div>
          </header>

          {renderContent()}

          <footer className="p-4 border-t border-border flex items-center gap-3">
            <button
              onClick={handleReset}
              className="flex-1 py-2.5 min-h-[44px] text-xs font-medium rounded-xl border border-border bg-bg text-muted hover:text-text transition-colors flex items-center justify-center"
            >
              Reset
            </button>
            <button
              onClick={() => onOpenChange(false)}
              className="flex-1 py-2.5 min-h-[44px] text-xs font-semibold rounded-xl bg-accent text-white hover:opacity-90 transition-opacity flex items-center justify-center"
            >
              Done
            </button>
          </footer>
        </aside>
      </div>
    );
  }

  // Non-modal Vaul sheet on mobile: no darkening veil so reader stays readable (R-10)
  return (
    <Drawer.Root open={open} onOpenChange={onOpenChange} modal={false}>
      <Drawer.Portal>
        <Drawer.Content className="fixed bottom-0 left-0 right-0 bg-surface/98 backdrop-blur-md rounded-t-2xl max-h-[85dvh] z-50 flex flex-col focus:outline-none shadow-2xl border-t border-border">
          <div className="w-12 h-1.5 bg-border rounded-full mx-auto my-3" />
          <header className="flex items-center justify-between px-5 pb-3 border-b border-border">
            <h4 className="font-semibold text-text text-sm">Appearance</h4>
            <button
              onClick={handleReset}
              className="text-xs text-muted hover:text-accent font-medium transition-colors min-h-[36px] px-2 flex items-center"
            >
              Reset
            </button>
          </header>

          {renderContent()}

          <footer className="p-4 border-t border-border bg-surface">
            <button
              onClick={() => onOpenChange(false)}
              className="w-full bg-accent text-white font-medium rounded-xl py-2.5 min-h-[44px] text-sm hover:opacity-90 transition-opacity flex items-center justify-center"
            >
              Done
            </button>
          </footer>
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  );
};
