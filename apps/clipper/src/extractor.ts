import { isProbablyReaderable, Readability } from "@mozilla/readability";

export interface ExtractedArticle {
  url: string;
  title: string;
  byline?: string;
  siteName?: string;
  lang?: string;
  publishedAt?: string;
  contentHtml: string;
  images: string[];
}

export function extractArticleFromDom(doc: Document, fallbackFullPage = false): ExtractedArticle | null {
  const url = doc.querySelector<HTMLLinkElement>('link[rel="canonical"]')?.href || doc.location.href;
  const siteName =
    doc.querySelector<HTMLMetaElement>('meta[property="og:site_name"]')?.content ||
    doc.location.hostname.replace(/^www\./, "");
  const lang = doc.documentElement.lang || "en";
  const publishedAt = doc.querySelector<HTMLMetaElement>(
    'meta[property="article:published_time"]'
  )?.content;

  // Clone document to preserve host DOM
  const clone = doc.cloneNode(true) as Document;

  if (!fallbackFullPage && !isProbablyReaderable(clone)) {
    return null;
  }

  const reader = new Readability(clone);
  const parsed = reader.parse();

  if (!parsed && !fallbackFullPage) {
    return null;
  }

  const contentHtml = parsed ? (parsed.content || "") : (doc.body ? doc.body.innerHTML : "");
  const title = (parsed && parsed.title) ? parsed.title : (doc.title || "Untitled Article");
  const byline = (parsed && parsed.byline) ? parsed.byline : undefined;

  // Collect image URLs and rewrite relative URLs in article content using DOMParser
  const parser = new DOMParser();
  const parsedDoc = parser.parseFromString(contentHtml, "text/html");
  const imgElements = Array.from(parsedDoc.querySelectorAll("img"));
  const imageUrls: string[] = [];

  for (const img of imgElements) {
    const src = img.getAttribute("src") || img.getAttribute("data-src");
    if (src && !src.startsWith("data:") && imageUrls.length < 40) {
      try {
        const resolved = new URL(src, url).href;
        if (!imageUrls.includes(resolved)) {
          imageUrls.push(resolved);
        }
        img.setAttribute("src", resolved);
      } catch {
        // Skip invalid URL
      }
    }
  }

  const finalHtml = parsedDoc.body ? parsedDoc.body.innerHTML : contentHtml;

  return {
    url,
    title,
    byline,
    siteName,
    lang,
    publishedAt,
    contentHtml: finalHtml,
    images: imageUrls
  };
}
