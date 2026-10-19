import DOMPurify from "dompurify";

/**
 * Section 6 Sanitizer rules:
 * Allowed elements: headings, paragraphs, lists, blockquote, pre, code, table elements,
 * figure, figcaption, img, a, em, strong, sup, sub, hr, br.
 * Forbid: script, style, iframe, object, form, svg, math, and every event-handler attribute.
 * Links get rel="noopener noreferrer".
 */
export const ALLOWED_TAGS = [
  "h1",
  "h2",
  "h3",
  "h4",
  "h5",
  "h6",
  "p",
  "ul",
  "ol",
  "li",
  "blockquote",
  "pre",
  "code",
  "table",
  "thead",
  "tbody",
  "tfoot",
  "tr",
  "th",
  "td",
  "figure",
  "figcaption",
  "img",
  "a",
  "em",
  "strong",
  "sup",
  "sub",
  "hr",
  "br"
];

export const FORBIDDEN_TAGS = [
  "script",
  "style",
  "iframe",
  "object",
  "embed",
  "form",
  "input",
  "textarea",
  "button",
  "select",
  "svg",
  "math"
];

export const ALLOWED_ATTR = [
  "href",
  "src",
  "alt",
  "title",
  "width",
  "height",
  "id",
  "class",
  "rel",
  "target",
  "colspan",
  "rowspan"
];

/**
 * Sanitizes dirty HTML conforming to Section 6.
 * Always run in browser / window context (or JSDOM in test environment).
 */
// Cache Node fallback window if needed
let nodeFallbackWindow: Window | undefined;

export function sanitizeArticleHtml(dirtyHtml: string, windowObj?: Window): string {
  let win = windowObj;
  if (!win) {
    if (typeof window !== "undefined") {
      win = window;
    } else {
      // In Node/Vitest environments, lazily instantiate JSDOM window if available
      if (!nodeFallbackWindow) {
        try {
          // eslint-disable-next-line @typescript-eslint/no-require-imports
          const { JSDOM } = require("jsdom");
          nodeFallbackWindow = new JSDOM("").window as unknown as Window;
        } catch (err) {
          console.warn("sanitizeArticleHtml: failed to load jsdom fallback window:", err);
        }
      }
      win = nodeFallbackWindow;
    }
  }

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const purifier = typeof DOMPurify === "function" ? (DOMPurify as any)(win) : DOMPurify;

  if (!purifier || !purifier.isSupported || typeof purifier.sanitize !== "function") {
    // Fail-safe: Never fail open if DOM/DOMPurify is unsupported.
    // HTML-escape everything to guarantee no dangerous payloads or bypasses survive.
    return dirtyHtml
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#39;");
  }

  // Add hook to enforce rel="noopener noreferrer" on all links if hooks are supported
  if (typeof purifier.addHook === "function") {
    purifier.addHook("afterSanitizeAttributes", (node: Element) => {
      if (node.tagName === "A" && node.hasAttribute("href")) {
        node.setAttribute("rel", "noopener noreferrer");
      }
    });
  }

  const clean = purifier.sanitize(dirtyHtml, {
    ALLOWED_TAGS,
    FORBID_TAGS: FORBIDDEN_TAGS,
    ALLOWED_ATTR,
    ALLOW_DATA_ATTR: false,
    FORBID_ATTR: [
      "onload",
      "onerror",
      "onclick",
      "onmouseover",
      "onfocus",
      "onblur",
      "onsubmit",
      "action"
    ]
  });

  if (typeof purifier.removeAllHooks === "function") {
    purifier.removeAllHooks();
  }

  return typeof clean === "string" ? clean : String(clean);
}
