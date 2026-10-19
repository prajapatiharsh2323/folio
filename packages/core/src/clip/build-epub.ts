import * as fflate from "fflate";
import { sha256Hex } from "../util/hash.js";
import { sanitizeArticleHtml } from "./sanitize.js";

export interface ClipPayload {
  version: 1;
  url: string;
  title: string;
  byline?: string;
  siteName?: string;
  lang?: string;
  publishedAt?: string;
  contentHtml: string; // unsanitized from clipper
  images: { originalUrl: string; mime: string; base64: string }[];
}

export interface ProcessedClip {
  title: string;
  author?: string;
  siteName?: string;
  sourceUrl: string;
  lang: string;
  cleanHtml: string;
  cleanIndexHtml: string;
  contentHash: string;
  images: { name: string; bytes: Uint8Array; mime: string }[];
  readingEpub: Uint8Array;
}

/**
 * Converts a base64 string to Uint8Array.
 */
function base64ToBytes(base64: string): Uint8Array {
  // Strip potential data URL prefix
  const cleanBase64 = base64.replace(/^data:[^;]+;base64,/, "");
  if (typeof atob === "function") {
    const binary = atob(cleanBase64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes;
  }
  // Node fallback
  const buf = Buffer.from(cleanBase64, "base64");
  return new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength);
}

/**
 * Escapes characters for strict XHTML compliance.
 */
function escapeXhtml(str: string): string {
  return str
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/**
 * Ensures XHTML void tags are self-closing and XML-safe entities.
 */
function toXhtml(html: string): string {
  // Escape bare & that is not already part of a valid XML entity reference
  const safeAmpHtml = html.replace(/&(?!(?:amp|lt|gt|quot|apos|#\d+|#x[a-f\d]+);)/gi, "&amp;");
  return safeAmpHtml
    .replace(/&nbsp;/g, "&#160;")
    .replace(/&copy;/g, "&#169;")
    .replace(/&reg;/g, "&#174;")
    .replace(/&trade;/g, "&#8482;")
    .replace(/&mdash;/g, "&#8212;")
    .replace(/&ndash;/g, "&#8211;")
    .replace(/&hellip;/g, "&#8230;")
    .replace(/&ldquo;/g, "&#8220;")
    .replace(/&rdquo;/g, "&#8221;")
    .replace(/&lsquo;/g, "&#8216;")
    .replace(/&rsquo;/g, "&#8217;")
    .replace(/&laquo;/g, "&#171;")
    .replace(/&raquo;/g, "&#187;")
    .replace(/&bull;/g, "&#8226;")
    .replace(/&middot;/g, "&#183;")
    .replace(/<img([^>]*)(?<!\/)>/gi, "<img$1 />")
    .replace(/<br([^>]*)(?<!\/)>/gi, "<br$1 />")
    .replace(/<hr([^>]*)(?<!\/)>/gi, "<hr$1 />")
    .replace(/<input([^>]*)(?<!\/)>/gi, "<input$1 />")
    .replace(/<wbr([^>]*)(?<!\/)>/gi, "<wbr$1 />");
}

/**
 * Processes a clip payload into sanitized HTML, downloaded images, and an EPUB 3 archive.
 */
export async function processClip(payload: ClipPayload, windowObj?: Window): Promise<ProcessedClip> {
  // 1. Sanitize the HTML
  let sanitized = sanitizeArticleHtml(payload.contentHtml, windowObj);

  // 2. Process and rewrite images
  const processedImages: { name: string; bytes: Uint8Array; mime: string }[] = [];

  for (const img of payload.images) {
    if (!img.base64) continue;
    const bytes = base64ToBytes(img.base64);
    const hash = await sha256Hex(bytes);
    const prefix = hash.substring(0, 16);

    let ext = "jpg";
    if (img.mime.includes("png")) ext = "png";
    else if (img.mime.includes("webp")) ext = "webp";
    else if (img.mime.includes("gif")) ext = "gif";

    const filename = `${prefix}.${ext}`;
    processedImages.push({ name: filename, bytes, mime: img.mime });

    // Rewrite img tags matching the originalUrl (handling raw URL, encoded &, and special regex chars)
    const urlsToMatch = [img.originalUrl];
    if (img.originalUrl.includes("&")) {
      urlsToMatch.push(img.originalUrl.replace(/&/g, "&amp;"));
    }
    for (const u of urlsToMatch) {
      const escapedUrl = u.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
      const regex = new RegExp(`src=["']${escapedUrl}["']`, "gi");
      sanitized = sanitized.replace(regex, `src="images/${filename}"`);
    }
  }

  // 3. Wrap body in minimal template (charset, lang, title, byline, source link)
  // Ensure template is deterministic without volatile runtime timestamps so duplicate content hashes match
  const lang = payload.lang || "en";
  const title = payload.title || "Untitled Article";
  const author = payload.byline || undefined;
  const siteName = payload.siteName || undefined;
  const articleDate = payload.publishedAt || "";

  const cleanIndexHtml = `<!DOCTYPE html>
<html lang="${escapeXhtml(lang)}">
<head>
  <meta charset="utf-8" />
  <title>${escapeXhtml(title)}</title>
  <meta name="author" content="${escapeXhtml(author || "")}" />
  ${articleDate ? `<meta name="date" content="${escapeXhtml(articleDate)}" />` : ""}
  <style>
    body { font-family: serif; line-height: 1.6; max-width: 680px; margin: 2rem auto; padding: 0 1rem; color: #1C1B19; background: #FAFAF7; }
    h1 { font-size: 1.8rem; margin-bottom: 0.25rem; color: #2F6F5E; }
    .meta { font-size: 0.85rem; color: #6B6860; margin-bottom: 2rem; border-bottom: 1px solid #E6E3DC; padding-bottom: 1rem; }
    img { max-width: 100%; height: auto; border-radius: 8px; }
    blockquote { border-left: 3px solid #2F6F5E; margin: 1.5rem 0; padding-left: 1rem; font-style: italic; color: #6B6860; }
  </style>
</head>
<body>
  <h1>${escapeXhtml(title)}</h1>
  <div class="meta">
    ${author ? `<span>By ${escapeXhtml(author)}</span> · ` : ""}
    ${siteName ? `<span>${escapeXhtml(siteName)}</span> · ` : ""}
    <a href="${escapeXhtml(payload.url)}" rel="noopener noreferrer" target="_blank">Original Article</a>
    ${articleDate ? `<span> · ${escapeXhtml(articleDate.substring(0, 10))}</span>` : ""}
  </div>
  <article>
    ${sanitized}
  </article>
</body>
</html>`;

  // Compute content hash from clean/index.html (Section 4: contentHash of clean/index.html for clips)
  const cleanIndexBytes = new TextEncoder().encode(cleanIndexHtml);
  const contentHash = await sha256Hex(cleanIndexBytes);

  // 4. Build EPUB 3 with fflate conforming to Section 6 item 4
  const zipObj: fflate.AsyncZippable = {};

  // mimetype stored uncompressed and first
  zipObj["mimetype"] = [fflate.strToU8("application/epub+zip"), { level: 0 }];

  // META-INF/container.xml
  zipObj["META-INF/container.xml"] = fflate.strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>`);

  // OEBPS/style.css
  zipObj["OEBPS/style.css"] = fflate.strToU8(`
body { font-family: serif; line-height: 1.6; margin: 5%; color: #1C1B19; }
h1, h2 { color: #2F6F5E; }
.meta { font-size: 0.85em; color: #6B6860; margin-bottom: 1.5em; }
img { max-width: 100%; height: auto; }
`);

  // Serialise chapter as XHTML with self-closed void tags and escaped entities
  const xhtmlBody = toXhtml(sanitized);
  const chapterXhtml = `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${escapeXhtml(lang)}" lang="${escapeXhtml(lang)}">
<head>
  <title>${escapeXhtml(title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css" />
</head>
<body>
  <h1>${escapeXhtml(title)}</h1>
  <div class="meta">
    ${author ? `<span>By ${escapeXhtml(author)}</span> · ` : ""}
    <a href="${escapeXhtml(payload.url)}">${escapeXhtml(siteName || "Source")}</a>
  </div>
  ${xhtmlBody}
</body>
</html>`;

  zipObj["OEBPS/chapter_1.xhtml"] = fflate.strToU8(chapterXhtml);

  // nav.xhtml
  zipObj["OEBPS/nav.xhtml"] = fflate.strToU8(`<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${escapeXhtml(lang)}">
<head>
  <title>Table of Contents</title>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Table of Contents</h1>
    <ol>
      <li><a href="chapter_1.xhtml">${escapeXhtml(title)}</a></li>
    </ol>
  </nav>
</body>
</html>`);

  // Images in EPUB
  const manifestItems = [
    `<item id="style" href="style.css" media-type="text/css" />`,
    `<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav" />`,
    `<item id="chapter_1" href="chapter_1.xhtml" media-type="application/xhtml+xml" />`
  ];

  for (let i = 0; i < processedImages.length; i++) {
    const img = processedImages[i]!;
    const id = `img_${i + 1}`;
    manifestItems.push(`<item id="${id}" href="images/${img.name}" media-type="${img.mime || "image/jpeg"}" />`);
    zipObj[`OEBPS/images/${img.name}`] = [img.bytes, { level: 0 }];
  }

  // Derive stable deterministic identifier from contentHash
  const stableUuid = `urn:uuid:${contentHash.substring(0, 8)}-${contentHash.substring(8, 12)}-${contentHash.substring(12, 16)}-${contentHash.substring(16, 20)}-${contentHash.substring(20, 32)}`;
  const modifiedDate = articleDate && !isNaN(new Date(articleDate).getTime())
    ? new Date(articleDate).toISOString().replace(/\.[0-9]+Z$/, "Z")
    : "2024-01-01T00:00:00Z";

  // OEBPS/content.opf
  zipObj["OEBPS/content.opf"] = fflate.strToU8(`<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">${stableUuid}</dc:identifier>
    <dc:title>${escapeXhtml(title)}</dc:title>
    <dc:language>${escapeXhtml(lang)}</dc:language>
    ${author ? `<dc:creator>${escapeXhtml(author)}</dc:creator>` : ""}
    <meta property="dcterms:modified">${modifiedDate}</meta>
  </metadata>
  <manifest>
    ${manifestItems.join("\n    ")}
  </manifest>
  <spine>
    <itemref idref="chapter_1" />
  </spine>
</package>`);

  const readingEpub = fflate.zipSync(zipObj);

  return {
    title,
    author,
    siteName,
    sourceUrl: payload.url,
    lang,
    cleanHtml: sanitized,
    cleanIndexHtml,
    contentHash,
    images: processedImages,
    readingEpub
  };
}
