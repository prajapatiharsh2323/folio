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

  try {
    const byTag = doc.getElementsByTagName("script");
    while (byTag.length > 0) {
      const el = byTag[0];
      el?.parentNode?.removeChild(el);
    }
  } catch {
    // ignore
  }

  try {
    const byTagNs = doc.getElementsByTagNameNS("*", "script");
    while (byTagNs.length > 0) {
      const el = byTagNs[0];
      el?.parentNode?.removeChild(el);
    }
  } catch {
    // ignore
  }

  try {
    const noScripts = doc.getElementsByTagName("noscript");
    while (noScripts.length > 0) {
      const el = noScripts[0];
      el?.parentNode?.removeChild(el);
    }
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
        } else if (name === "src" || name === "href" || name === "xlink:href" || name === "action") {
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
 * Removes residual script tags, inline event attributes, and disarms javascript: URIs.
 */
export function sanitizeSerializedHtml(output: string, section?: { output?: string }): string {
  if (!output || typeof output !== "string") return output || "";
  let clean = output;
  // Strip any <script ...>...</script> (including multiline, whitespace, uppercase, etc.)
  clean = clean.replace(/<script\b[^<]*(?:(?!<\/script>)<[^<]*)*<\/script\s*>/gi, "");
  // Strip self-closing <script ... />
  clean = clean.replace(/<script\b[^>]*\/>/gi, "");
  // Strip <noscript ...>...</noscript>
  clean = clean.replace(/<noscript\b[^<]*(?:(?!<\/noscript>)<[^<]*)*<\/noscript\s*>/gi, "");
  // Strip inline on* event handlers (e.g. onload="...", onclick='...', onerror=...)
  clean = clean.replace(/\s+on[a-z0-9_-]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, "");
  // Neutralize javascript: URLs
  clean = clean.replace(/\s+(href|src|xlink:href)\s*=\s*(["'])\s*javascript:[^"']*\2/gi, ' $1="about:blank"');

  if (section) {
    section.output = clean;
  }
  return clean;
}
