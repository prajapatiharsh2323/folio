import React, { useState, useEffect } from "react";
import { Drawer } from "vaul";
import {
  Sun,
  Coffee,
  Moon,
  Laptop,
  AlignLeft,
  AlignJustify,
  RotateCcw,
  X,
  BookOpen,
  ScrollText,
  Columns2,
  RectangleVertical,
  Sparkles
} from "lucide-react";
import { useReaderStore, DEFAULT_READING_PREFS } from "../state/library-store.js";
import type { ReadingPrefs } from "@folio/core";

interface TypographySheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}

const PRESETS: { id: string; label: string; desc: string; prefs: Partial<ReadingPrefs> }[] = [
  {
    id: "publisher",
    label: "Classic Book",
    desc: "Serif · Paginated · Balanced",
    prefs: {
      fontFamily: "serif",
      fontSize: 18,
      lineHeight: 1.65,
      margin: 24,
      paragraphSpacing: 0.4,
      letterSpacing: 0,
      justify: true,
      hyphenate: true,
      flow: "paginated"
    }
  },
  {
    id: "cozy",
    label: "Cozy Night",
    desc: "Warm · Relaxed spacing",
    prefs: {
      theme: "warm",
      fontFamily: "serif",
      fontSize: 20,
      lineHeight: 1.8,
      margin: 28,
      paragraphSpacing: 0.6,
      letterSpacing: 0.01,
      justify: false,
      hyphenate: false,
      flow: "paginated"
    }
  },
  {
    id: "editorial",
    label: "Modern Article",
    desc: "Sans · Continuous scroll",
    prefs: {
      fontFamily: "sans",
      fontSize: 17,
      lineHeight: 1.7,
      margin: 20,
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
  const [activeTab, setActiveTab] = useState<"style" | "layout">("style");
  const [isDesktop, setIsDesktop] = useState(
    typeof window !== "undefined" ? window.innerWidth >= 768 : false
  );
  const [viewportWidth, setViewportWidth] = useState(
    typeof window !== "undefined" ? window.innerWidth : 1024
  );

  useEffect(() => {
    const handleResize = () => {
      setIsDesktop(window.innerWidth >= 768);
      setViewportWidth(window.innerWidth);
    };
    window.addEventListener("resize", handleResize);
    return () => window.removeEventListener("resize", handleResize);
  }, []);

  const handleReset = () => {
    setPrefs(DEFAULT_READING_PREFS);
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
            {prefs.fontSize}px · {prefs.lineHeight}x · {prefs.flow}
          </span>
        </div>
        <p
          style={{
            fontFamily:
              prefs.fontFamily === "serif"
                ? '"Literata", "Noto Serif Devanagari", Georgia, serif'
                : prefs.fontFamily === "mono"
                ? "ui-monospace, SFMono-Regular, monospace"
                : 'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans Devanagari", sans-serif',
            fontSize: `${Math.min(Math.max(prefs.fontSize, 14), 22)}px`,
            lineHeight: prefs.lineHeight,
            letterSpacing: `${prefs.letterSpacing ?? 0}em`,
            textAlign: prefs.justify ? "justify" : "left",
            hyphens: prefs.hyphenate ? "auto" : "manual"
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
          className={`py-1.5 rounded-lg transition-all ${
            activeTab === "style"
              ? "bg-surface text-accent shadow-xs"
              : "text-muted hover:text-text"
          }`}
        >
          Theme & Typography
        </button>
        <button
          onClick={() => setActiveTab("layout")}
          className={`py-1.5 rounded-lg transition-all ${
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
              {PRESETS.map((preset) => (
                <button
                  key={preset.id}
                  onClick={() => setPrefs(preset.prefs)}
                  className="p-2.5 rounded-xl border border-border bg-bg hover:border-accent text-left transition-all"
                >
                  <div className="text-xs font-semibold text-text">{preset.label}</div>
                  <div className="text-[10px] text-muted truncate mt-0.5">{preset.desc}</div>
                </button>
              ))}
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
                  aria-checked={prefs.theme === id}
                  onClick={() => setPrefs({ theme: id })}
                  className={`flex flex-col items-center justify-center gap-1 py-2 px-2 rounded-lg border text-xs font-medium transition-all ${
                    prefs.theme === id
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
                <span className="font-mono text-xs">{prefs.brightness ?? 100}%</span>
              </div>
              <input
                id="brightness-slider"
                type="range"
                min="40"
                max="100"
                step="5"
                value={prefs.brightness ?? 100}
                aria-valuetext={`${prefs.brightness ?? 100}%`}
                onChange={(e) => setPrefs({ brightness: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer"
              />
            </div>
          </div>

          {/* 2. Typeface Section */}
          <div>
            <span className="text-xs font-semibold uppercase tracking-wider text-muted block mb-2">
              Typeface
            </span>
            <div
              role="radiogroup"
              aria-label="Font family selection"
              className="grid grid-cols-3 gap-2"
            >
              <button
                role="radio"
                aria-checked={prefs.fontFamily === "serif"}
                onClick={() => setPrefs({ fontFamily: "serif" })}
                className={`py-2 px-2.5 rounded-lg border text-xs font-serif transition-all ${
                  prefs.fontFamily === "serif"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                Literata Serif
              </button>
              <button
                role="radio"
                aria-checked={prefs.fontFamily === "sans"}
                onClick={() => setPrefs({ fontFamily: "sans" })}
                className={`py-2 px-2.5 rounded-lg border text-xs font-sans transition-all ${
                  prefs.fontFamily === "sans"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                System Sans
              </button>
              <button
                role="radio"
                aria-checked={prefs.fontFamily === "mono"}
                onClick={() => setPrefs({ fontFamily: "mono" })}
                className={`py-2 px-2.5 rounded-lg border text-xs font-mono transition-all ${
                  prefs.fontFamily === "mono"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                Monospace
              </button>
            </div>
          </div>

          {/* 3. Font Size Section */}
          <div>
            <div className="flex items-center justify-between text-xs font-semibold uppercase tracking-wider text-muted mb-2">
              <label htmlFor="font-size-slider">Font Size</label>
              <span className="font-mono">{prefs.fontSize}px</span>
            </div>
            <div className="flex items-center gap-3">
              <button
                onClick={() => setPrefs({ fontSize: Math.max(14, prefs.fontSize - 1) })}
                className="w-10 h-9 border border-border rounded-lg bg-bg text-sm font-bold flex items-center justify-center hover:bg-border/30"
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
                value={prefs.fontSize}
                aria-valuetext={`${prefs.fontSize} pixels`}
                onChange={(e) => setPrefs({ fontSize: Number(e.target.value) })}
                className="flex-1 accent-accent cursor-pointer"
              />
              <button
                onClick={() => setPrefs({ fontSize: Math.min(32, prefs.fontSize + 1) })}
                className="w-10 h-9 border border-border rounded-lg bg-bg text-sm font-bold flex items-center justify-center hover:bg-border/30"
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
                aria-checked={prefs.flow === "paginated"}
                onClick={() => setPrefs({ flow: "paginated" })}
                className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-lg border text-xs transition-all ${
                  prefs.flow === "paginated"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                <BookOpen className="w-4 h-4" />
                <span>Paginated</span>
              </button>
              <button
                role="radio"
                aria-checked={prefs.flow === "scrolled"}
                onClick={() => setPrefs({ flow: "scrolled" })}
                className={`flex items-center justify-center gap-2 py-2.5 px-3 rounded-lg border text-xs transition-all ${
                  prefs.flow === "scrolled"
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                <ScrollText className="w-4 h-4" />
                <span>Continuous</span>
              </button>
            </div>

            {/* Two-Column Spread on Wide Viewports */}
            {viewportWidth >= 1024 && prefs.flow === "paginated" && (
              <div className="grid grid-cols-2 gap-2 mt-2">
                <button
                  onClick={() => setPrefs({ spread: "none" })}
                  className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg border text-xs transition-all ${
                    (prefs.spread ?? "none") === "none"
                      ? "border-accent bg-accent/10 text-accent font-semibold"
                      : "border-border bg-bg text-text"
                  }`}
                >
                  <RectangleVertical className="w-3.5 h-3.5" />
                  <span>Single Column</span>
                </button>
                <button
                  onClick={() => setPrefs({ spread: "auto" })}
                  className={`flex items-center justify-center gap-1.5 py-2 px-3 rounded-lg border text-xs transition-all ${
                    prefs.spread === "auto"
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
                onClick={() => setPrefs({ justify: !prefs.justify })}
                className={`flex items-center justify-center gap-2 py-2 px-3 rounded-lg border text-xs font-medium transition-all ${
                  prefs.justify
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                {prefs.justify ? (
                  <AlignJustify className="w-4 h-4" />
                ) : (
                  <AlignLeft className="w-4 h-4" />
                )}
                <span>{prefs.justify ? "Justified" : "Left Aligned"}</span>
              </button>

              <button
                onClick={() => setPrefs({ hyphenate: !prefs.hyphenate })}
                className={`flex items-center justify-center gap-2 py-2 px-3 rounded-lg border text-xs font-medium transition-all ${
                  prefs.hyphenate
                    ? "border-accent bg-accent/10 text-accent font-semibold ring-1 ring-accent"
                    : "border-border bg-bg text-text hover:bg-border/30"
                }`}
              >
                <span>Hyphenate: {prefs.hyphenate ? "On" : "Off"}</span>
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
                <span className="font-mono">{prefs.lineHeight}</span>
              </div>
              <input
                id="line-height-slider"
                type="range"
                min="1.3"
                max="2.4"
                step="0.05"
                value={prefs.lineHeight}
                aria-valuetext={`${prefs.lineHeight}`}
                onChange={(e) => setPrefs({ lineHeight: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer"
              />
            </div>

            {/* Page Margins */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="margin-slider">Horizontal Margin (Gutters)</label>
                <span className="font-mono">{prefs.margin}px</span>
              </div>
              <input
                id="margin-slider"
                type="range"
                min="12"
                max="64"
                step="4"
                value={prefs.margin}
                aria-valuetext={`${prefs.margin} pixels`}
                onChange={(e) => setPrefs({ margin: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer"
              />
            </div>

            {/* Paragraph Spacing */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="para-spacing-slider">Paragraph Spacing</label>
                <span className="font-mono">{prefs.paragraphSpacing ?? 0.5}em</span>
              </div>
              <input
                id="para-spacing-slider"
                type="range"
                min="0"
                max="1.5"
                step="0.1"
                value={prefs.paragraphSpacing ?? 0.5}
                aria-valuetext={`${prefs.paragraphSpacing ?? 0.5} em`}
                onChange={(e) => setPrefs({ paragraphSpacing: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer"
              />
            </div>

            {/* Letter Spacing */}
            <div>
              <div className="flex items-center justify-between text-xs text-muted mb-1">
                <label htmlFor="letter-spacing-slider">Letter Spacing</label>
                <span className="font-mono">{prefs.letterSpacing ?? 0}em</span>
              </div>
              <input
                id="letter-spacing-slider"
                type="range"
                min="-0.02"
                max="0.08"
                step="0.01"
                value={prefs.letterSpacing ?? 0}
                aria-valuetext={`${prefs.letterSpacing ?? 0} em`}
                onChange={(e) => setPrefs({ letterSpacing: Number(e.target.value) })}
                className="w-full accent-accent cursor-pointer"
              />
            </div>

            {/* Reading Width (When viewport >= 768px) */}
            {viewportWidth >= 768 && (
              <div>
                <div className="flex items-center justify-between text-xs text-muted mb-1">
                  <label htmlFor="max-width-slider">Max Reading Column Width</label>
                  <span className="font-mono">{prefs.maxWidth ?? 720}px</span>
                </div>
                <input
                  id="max-width-slider"
                  type="range"
                  min="560"
                  max="960"
                  step="20"
                  value={prefs.maxWidth ?? 720}
                  aria-valuetext={`${prefs.maxWidth ?? 720} pixels`}
                  onChange={(e) => setPrefs({ maxWidth: Number(e.target.value) })}
                  className="w-full accent-accent cursor-pointer"
                />
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );

  if (!open) return null;

  if (isDesktop) {
    return (
      <div className="fixed inset-0 z-50 flex justify-end">
        <div
          className="fixed inset-0 bg-black/40 backdrop-blur-xs transition-opacity"
          onClick={() => onOpenChange(false)}
        />
        <aside className="relative w-[380px] max-w-[90vw] h-full bg-surface border-l border-border flex flex-col z-10 shadow-2xl animate-in slide-in-from-right duration-200">
          <header className="flex items-center justify-between px-5 py-3.5 border-b border-border">
            <h4 className="font-semibold text-text text-sm">Appearance</h4>
            <div className="flex items-center gap-2">
              <button
                onClick={handleReset}
                title="Reset to defaults"
                className="flex items-center gap-1 text-xs px-2.5 py-1 rounded-md bg-bg text-muted hover:text-text transition-colors border border-border"
              >
                <RotateCcw className="w-3 h-3" />
                <span>Reset</span>
              </button>
              <button
                onClick={() => onOpenChange(false)}
                className="p-1 rounded-md hover:bg-bg text-muted hover:text-text transition-colors"
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
              className="flex-1 py-2 text-xs font-medium rounded-xl border border-border bg-bg text-muted hover:text-text transition-colors"
            >
              Reset
            </button>
            <button
              onClick={() => onOpenChange(false)}
              className="flex-1 py-2 text-xs font-semibold rounded-xl bg-accent text-white hover:opacity-90 transition-opacity"
            >
              Done
            </button>
          </footer>
        </aside>
      </div>
    );
  }

  return (
    <Drawer.Root open={open} onOpenChange={onOpenChange}>
      <Drawer.Portal>
        <Drawer.Overlay className="fixed inset-0 bg-black/40 backdrop-blur-xs z-50 transition-opacity" />
        <Drawer.Content className="fixed bottom-0 left-0 right-0 bg-surface rounded-t-2xl max-h-[85dvh] z-50 flex flex-col focus:outline-none shadow-2xl border-t border-border">
          <div className="w-12 h-1.5 bg-border rounded-full mx-auto my-3" />
          <header className="flex items-center justify-between px-5 pb-3 border-b border-border">
            <h4 className="font-semibold text-text text-sm">Appearance</h4>
            <button
              onClick={handleReset}
              className="text-xs text-muted hover:text-accent font-medium transition-colors"
            >
              Reset
            </button>
          </header>

          {renderContent()}

          <footer className="p-4 border-t border-border bg-surface">
            <button
              onClick={() => onOpenChange(false)}
              className="w-full bg-accent text-white font-medium rounded-xl py-2.5 text-sm hover:opacity-90 transition-opacity"
            >
              Done
            </button>
          </footer>
        </Drawer.Content>
      </Drawer.Portal>
    </Drawer.Root>
  );
};
