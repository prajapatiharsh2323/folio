/**
 * Strips executable script tags, inline event listeners, and javascript: links from an
 * XML/XHTML chapter document before it is serialized for rendering inside a sandboxed iframe.
 */
export function sanitizeSectionDoc(doc: Document): void {
  if (!doc) return;

  // 1. Remove all <script> and <noscript> elements
  try {
    const scripts = doc.querySelectorAll("script, noscript");
    scripts.forEach((el) => el.remove());
  } catch {
    // ignore querySelectorAll errors on older or non-standard DOMs
  }

  // Also sweep with getElementsByTagNameNS and localName to catch namespaced XML elements (<xhtml:script>, <epub:script>, etc.)
  try {
    const scriptsNS = Array.from(doc.getElementsByTagNameNS("*", "script"));
    scriptsNS.forEach((el) => el.remove());
  } catch {
    // ignore
  }
  try {
    const noscriptsNS = Array.from(doc.getElementsByTagNameNS("*", "noscript"));
    noscriptsNS.forEach((el) => el.remove());
  } catch {
    // ignore
  }
  try {
    const all = doc.querySelectorAll("*");
    all.forEach((el) => {
      const tag = (el.localName || el.tagName || "").toLowerCase();
      if (tag === "script" || tag === "noscript" || tag.endsWith(":script") || tag.endsWith(":noscript")) {
        el.remove();
      }
    });
  } catch {
    // ignore
  }

  // 2. Remove all inline on* event attributes (onload, onclick, onerror, etc.) and disarm javascript: URLs
  try {
    const elements = doc.querySelectorAll("*");
    elements.forEach((el) => {
      const attrNames = el.getAttributeNames();
      for (const name of attrNames) {
        if (/^on/i.test(name)) {
          el.removeAttribute(name);
        } else if (name === "src" || name === "href" || name === "xlink:href" || name === "action" || name === "formaction") {
          const val = el.getAttribute(name) || "";
          if (/^\s*javascript:/i.test(val)) {
            el.removeAttribute(name);
          }
        }
      }
    });
  } catch {
    // ignore
  }
}

/**
 * Sanitizes serialized HTML string before epub.js assigns it to iframe.srcdoc.
 * Removes residual script tags (including namespaced XML tags), meta refresh,
 * inline event attributes, and disarms javascript: URIs.
 */
export function sanitizeSerializedHtml(output: string, section?: { output?: string }): string {
  if (!output || typeof output !== "string") return output || "";
  let clean = output;
  // Self-closing scripts FIRST: otherwise the lazy block pattern below would start at "<script src=x/>"
  // and swallow all real content up to the next "</script>".
  clean = clean.replace(/<([a-zA-Z0-9_-]+:)?script\b[^>]*\/>/gi, "");
  // <script ...>...</script> including namespaced XML tags (<xhtml:script>, <epub:script>, ...)
  clean = clean.replace(/<([a-zA-Z0-9_-]+:)?script\b[\s\S]*?<\/([a-zA-Z0-9_-]+:)?script\s*>/gi, "");
  clean = clean.replace(/<([a-zA-Z0-9_-]+:)?noscript\b[^>]*\/>/gi, "");
  clean = clean.replace(/<([a-zA-Z0-9_-]+:)?noscript\b[\s\S]*?<\/([a-zA-Z0-9_-]+:)?noscript\s*>/gi, "");
  // <meta http-equiv="refresh">
  clean = clean.replace(/<meta\b[^>]*http-equiv\s*=\s*["']?refresh["']?[^>]*>/gi, "");
  // Inline on* handlers and javascript: URLs -- only INSIDE start tags, so visible prose such as
  // " one = 5" or " online=true" is never edited.
  clean = clean.replace(/<[a-zA-Z][^<>]*>/g, (tag) =>
    tag
      .replace(/\s+on[a-zA-Z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, "")
      .replace(/\s+(href|src|xlink:href|action|formaction)\s*=\s*(["'])\s*javascript:[^"']*\2/gi, ' $1="about:blank"')
  );

  if (section) {
    section.output = clean;
  }
  return clean;
}
