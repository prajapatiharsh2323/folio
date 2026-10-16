// Reader fonts.
//
// The book is rendered inside an <iframe> (epub.js), which does NOT inherit @font-face rules from the
// host page. These imports register the fonts on the host page (so the Appearance sheet preview works and
// the files get precached by the service worker); `collectFontFaceCss()` then copies the resulting
// @font-face rules into every book document with absolute URLs.
import "@fontsource/literata/400.css";
import "@fontsource/literata/400-italic.css";
import "@fontsource/literata/700.css";
import "@fontsource/lora/400.css";
import "@fontsource/lora/400-italic.css";
import "@fontsource/lora/500.css";
import "@fontsource/lora/700.css";
import "@fontsource/noto-serif-devanagari/400.css";
import "@fontsource/noto-serif-devanagari/700.css";
import "@fontsource/noto-sans-devanagari/400.css";
import "@fontsource/noto-sans-devanagari/700.css";

export const SERIF_STACK =
  '"Literata", "Noto Serif Devanagari", "Iowan Old Style", "Palatino Linotype", Georgia, "Nirmala UI", "Kohinoor Devanagari", "Devanagari Sangam MN", serif';

export const LORA_STACK =
  '"Lora", "Literata", "Noto Serif Devanagari", "Iowan Old Style", Georgia, "Nirmala UI", "Kohinoor Devanagari", "Devanagari Sangam MN", serif';

export const SANS_STACK =
  'system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans Devanagari", "Nirmala UI", "Kohinoor Devanagari", "Devanagari Sangam MN", sans-serif';

const FAMILIES = ["Literata", "Lora", "Noto Serif Devanagari", "Noto Sans Devanagari"];

let cached = "";

/**
 * Returns the @font-face rules of the bundled reader fonts with every url() made absolute, so they
 * keep working when injected into the book's iframe document.
 */
export function collectFontFaceCss(): string {
  if (cached) return cached;
  if (typeof document === "undefined") return "";

  const out: string[] = [];
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList;
    try {
      rules = sheet.cssRules;
    } catch {
      continue; // cross-origin stylesheet
    }
    const base = sheet.href || document.baseURI || location.href;
    for (const rule of Array.from(rules)) {
      if (!(rule instanceof CSSFontFaceRule)) continue;
      const family = rule.style.getPropertyValue("font-family").replace(/["']/g, "").trim();
      if (!FAMILIES.some((f) => family === f)) continue;
      const css = rule.cssText.replace(
        /url\(\s*(["']?)([^)"']+)\1\s*\)/g,
        (_m, _q: string, url: string) => {
          if (/^(data:|blob:|https?:)/i.test(url)) return `url("${url}")`;
          try {
            return `url("${new URL(url, base).href}")`;
          } catch {
            return `url("${url}")`;
          }
        }
      );
      out.push(css);
    }
  }

  // Only cache a complete result; stylesheets may not have been parsed yet on the first call.
  if (out.length >= FAMILIES.length) cached = out.join("\n");
  return out.join("\n");
}
