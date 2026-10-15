import React, { useState, useRef, useEffect } from "react";
import {
  Check,
  MoreVertical,
  BookOpen,
  CheckCircle2,
  RotateCcw,
  Info,
  Trash2,
  Palette
} from "lucide-react";
import type { DocMeta, Progress } from "@folio/core";

export const HARMONIC_HUES = [
  { hue: 135, name: "Moss", color: "oklch(0.68 0.12 135)" },
  { hue: 195, name: "Teal", color: "oklch(0.68 0.12 195)" },
  { hue: 250, name: "Ink Blue", color: "oklch(0.68 0.12 250)" },
  { hue: 285, name: "Iris", color: "oklch(0.68 0.12 285)" },
  { hue: 330, name: "Plum", color: "oklch(0.68 0.12 330)" },
  { hue: 10, name: "Rose", color: "oklch(0.68 0.12 10)" },
  { hue: 50, name: "Clay", color: "oklch(0.68 0.12 50)" },
  { hue: 90, name: "Ochre", color: "oklch(0.68 0.12 90)" }
] as const;

export function getBookHue(doc: DocMeta, customHue?: number): number {
  if (typeof customHue === "number") return customHue;
  const str = doc.title || doc.id || "folio";
  let h = 7;
  for (let i = 0; i < str.length; i++) {
    h = ((h * 31) + str.charCodeAt(i)) >>> 0;
  }
  return HARMONIC_HUES[h % HARMONIC_HUES.length]!.hue;
}

export interface BookCardProps {
  doc: DocMeta;
  progress?: Progress;
  isSelected?: boolean;
  onSelectToggle?: (docId: string) => void;
  onOpen: (doc: DocMeta) => void;
  onOpenDetails: (doc: DocMeta) => void;
  onDelete?: (docId: string) => void;
  onToggleFinished?: (docId: string, isFinished: boolean) => void;
  customHue?: number;
  onHueChange?: (docId: string, hue: number) => void;
}

export const BookCard: React.FC<BookCardProps> = ({
  doc,
  progress,
  isSelected = false,
  onSelectToggle,
  onOpen,
  onOpenDetails,
  onDelete,
  onToggleFinished,
  customHue,
  onHueChange
}) => {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const menuBtnRef = useRef<HTMLButtonElement>(null);

  const hue = getBookHue(doc, customHue);
  const percent = progress?.percent ?? 0;
  const isFinished = percent >= 100;
  const isHindi = doc.language === "hi" || /[\u0900-\u097F]/.test(doc.title);

  // Close menu on click outside or Escape
  useEffect(() => {
    if (!menuOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (
        menuRef.current &&
        !menuRef.current.contains(e.target as Node) &&
        menuBtnRef.current &&
        !menuBtnRef.current.contains(e.target as Node)
      ) {
        setMenuOpen(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setMenuOpen(false);
        menuBtnRef.current?.focus();
      }
    };

    document.addEventListener("mousedown", handleClickOutside);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handleClickOutside);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [menuOpen]);

  const handleCardClick = () => {
    onOpen(doc);
  };

  const handleSelectClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if ("vibrate" in navigator) {
      try {
        navigator.vibrate(8);
      } catch {}
    }
    onSelectToggle?.(doc.id);
  };

  const handleMoreClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    setMenuOpen((prev) => !prev);
  };

  const handleCycleColor = (e: React.MouseEvent) => {
    e.stopPropagation();
    const currentIdx = HARMONIC_HUES.findIndex((h) => h.hue === hue);
    const nextIdx = (currentIdx + 1) % HARMONIC_HUES.length;
    onHueChange?.(doc.id, HARMONIC_HUES[nextIdx]!.hue);
  };

  return (
    <article
      data-testid={`book-card-${doc.id}`}
      data-selected={isSelected}
      data-done={isFinished}
      onClick={handleCardClick}
      className="folio-book-card group cursor-pointer"
      style={
        {
          "--h": hue
        } as React.CSSProperties
      }
    >
      {/* Top Controls Row */}
      <div className="flex items-center justify-between -mt-1 -mx-1 relative z-20">
        {/* Selection Checkbox */}
        <button
          type="button"
          aria-label={isSelected ? `Deselect ${doc.title}` : `Select ${doc.title}`}
          aria-pressed={isSelected}
          onClick={handleSelectClick}
          className="w-10 h-10 flex items-center justify-center rounded-xl cursor-pointer group/sel"
        >
          <span
            className={`w-6 h-6 rounded-[7px] flex items-center justify-center transition-all duration-150 ${
              isSelected
                ? "bg-[var(--ink)] text-[var(--bg)] shadow-xs scale-105"
                : "border-[1.75px] border-current opacity-55 group-hover/sel:opacity-95 group-hover/sel:bg-[var(--card-chip-hover)]"
            }`}
          >
            <Check
              className={`w-3.5 h-3.5 stroke-[3] transition-transform duration-150 ${
                isSelected ? "scale-100 opacity-100" : "scale-50 opacity-0"
              }`}
            />
          </span>
        </button>

        {/* Format Pill (Center) */}
        <div className="flex items-center gap-1.5 pointer-events-none">
          <span className="text-[10px] font-mono tracking-wider font-semibold uppercase px-2 py-0.5 rounded-full bg-[var(--card-chip)] text-[var(--ink)] opacity-80">
            {doc.sourceFormat}
          </span>
        </div>

        {/* More Actions Menu Button */}
        <button
          ref={menuBtnRef}
          type="button"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
          aria-label={`More actions for ${doc.title}`}
          onClick={handleMoreClick}
          className="w-10 h-10 flex items-center justify-center rounded-xl cursor-pointer group/more"
        >
          <span className="w-7 h-7 rounded-lg flex items-center justify-center bg-[var(--card-chip)] group-hover/more:bg-[var(--card-chip-hover)] transition-colors">
            <MoreVertical className="w-4 h-4 text-current" />
          </span>
        </button>
      </div>

      {/* Book Title */}
      <div className="mt-2.5 flex-1 flex flex-col justify-start relative z-10">
        <h3
          className={`font-semibold tracking-[-0.012em] text-balance break-words line-clamp-5 leading-[1.22] text-current ${
            isHindi
              ? "text-[18px] sm:text-[20px]"
              : "text-[17px] sm:text-[19px] md:text-[21px]"
          }`}
          style={{
            fontFamily: isHindi
              ? '"Noto Serif Devanagari", "Literata", Georgia, serif'
              : '"Literata", "Noto Serif Devanagari", Georgia, serif'
          }}
        >
          <span className="hover:underline decoration-1 underline-offset-3">
            {doc.title}
          </span>
        </h3>
      </div>

      {/* Tags and Author */}
      <div className="mt-auto pt-2 relative z-10 flex flex-col justify-end">
        {doc.tags && doc.tags.length > 0 && (
          <div className="flex items-center gap-1 mb-1 overflow-hidden text-[11px] opacity-75 truncate">
            <span className="truncate">#{doc.tags[0]}</span>
            {doc.tags.length > 1 && (
              <span className="opacity-60">+{doc.tags.length - 1}</span>
            )}
          </div>
        )}
        <p className="text-right text-[12px] sm:text-[13px] text-[var(--sub)] font-medium truncate">
          — {doc.author || "Unknown"}
        </p>
      </div>

      {/* Bottom Spine Reading Progress Band */}
      <div
        className="absolute left-0 right-0 bottom-0 h-[5px] bg-[var(--card-spine-bg)]"
        role="progressbar"
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-label="Reading progress"
      >
        <div
          className="h-full bg-[var(--ink)] rounded-r-xs transition-[width] duration-300"
          style={{
            width: `${Math.min(100, Math.max(0, percent))}%`,
            opacity: isFinished ? 0.65 : 1
          }}
        />
      </div>

      {/* Action Popover Menu */}
      {menuOpen && (
        <div
          ref={menuRef}
          onClick={(e) => e.stopPropagation()}
          className="absolute right-2.5 top-12 z-50 min-w-[190px] p-1.5 rounded-2xl bg-surface/98 backdrop-blur-md border border-border shadow-2xl text-text animate-in fade-in zoom-in-95 duration-120 origin-top-right text-xs"
        >
          {/* Open Reader */}
          <button
            onClick={() => {
              setMenuOpen(false);
              onOpen(doc);
            }}
            className="w-full flex items-center gap-2.5 px-3 py-2 rounded-xl hover:bg-bg transition-colors text-left font-medium text-text"
          >
            <BookOpen className="w-4 h-4 text-muted" />
            <span>Open Book</span>
          </button>

          {/* Color Palette Picker Row */}
          <div className="px-3 py-2 border-y border-border/60 my-1">
            <div className="flex items-center justify-between mb-1.5">
              <span className="text-[10px] font-semibold text-muted uppercase tracking-wider">
                Palette Hue
              </span>
              <button
                onClick={handleCycleColor}
                title="Cycle to next color"
                className="text-[10px] text-accent hover:underline flex items-center gap-1 font-medium"
              >
                <Palette className="w-3 h-3" />
                <span>Next</span>
              </button>
            </div>
            <div className="grid grid-cols-4 gap-1.5 pt-0.5">
              {HARMONIC_HUES.map((item) => (
                <button
                  key={item.hue}
                  title={item.name}
                  onClick={() => {
                    onHueChange?.(doc.id, item.hue);
                  }}
                  className={`w-6 h-6 rounded-full flex items-center justify-center transition-transform hover:scale-115 border ${
                    hue === item.hue
                      ? "ring-2 ring-accent ring-offset-1 border-white"
                      : "border-black/10"
                  }`}
                  style={{
                    background: item.color
                  }}
                />
              ))}
            </div>
          </div>

          {/* Toggle Finished/Unread */}
          <button
            onClick={() => {
              setMenuOpen(false);
              onToggleFinished?.(doc.id, !isFinished);
            }}
            className="w-full flex items-center gap-2.5 px-3 py-2 rounded-xl hover:bg-bg transition-colors text-left text-text"
          >
            {isFinished ? (
              <>
                <RotateCcw className="w-4 h-4 text-muted" />
                <span>Mark as Unread</span>
              </>
            ) : (
              <>
                <CheckCircle2 className="w-4 h-4 text-accent" />
                <span>Mark as Finished</span>
              </>
            )}
          </button>

          {/* Document Details */}
          <button
            onClick={() => {
              setMenuOpen(false);
              onOpenDetails(doc);
            }}
            className="w-full flex items-center gap-2.5 px-3 py-2 rounded-xl hover:bg-bg transition-colors text-left text-text"
          >
            <Info className="w-4 h-4 text-muted" />
            <span>Document Details</span>
          </button>

          {/* Delete */}
          {onDelete && (
            <button
              onClick={() => {
                setMenuOpen(false);
                if (window.confirm(`Delete "${doc.title}" from your library?`)) {
                  onDelete(doc.id);
                }
              }}
              className="w-full flex items-center gap-2.5 px-3 py-2 rounded-xl hover:bg-red-500/10 text-red-600 transition-colors text-left"
            >
              <Trash2 className="w-4 h-4" />
              <span>Delete Book</span>
            </button>
          )}
        </div>
      )}
    </article>
  );
};
