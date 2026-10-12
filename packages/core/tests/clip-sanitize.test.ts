import { describe, it, expect } from "vitest";
import { JSDOM } from "jsdom";
import { sanitizeArticleHtml } from "../src/clip/sanitize.js";
import { detectFileFormat } from "../src/import/detect.js";
import { importTxt } from "../src/import/txt.js";
import { importHtml } from "../src/import/html.js";
import { processClip } from "../src/clip/build-epub.js";

const dom = new JSDOM("");
const testWindow = dom.window as unknown as Window;

describe("Phase 2 Article Clipper Sanitizer & Input Acceptance Tests", () => {
  it("completely strips script tags, event handlers, javascript: links and iframes", () => {
    const maliciousHtml = `
      <div>
        <h1>Legitimate Article Title</h1>
        <script>alert('pwned');</script>
        <p onclick="alert('click')">Clickable text</p>
        <img src="https://example.com/pic.jpg" onload="alert('img')" onerror="stealCookies()" />
        <a href="javascript:alert('xss')">Dangerous link</a>
        <a href="https://example.com/valid">Safe link</a>
        <iframe src="https://evil.com/frame"></iframe>
        <form action="/steal"><input type="password" value="secret"/></form>
        <svg><text>SVG Vector</text></svg>
      </div>
    `;

    const cleaned = sanitizeArticleHtml(maliciousHtml, testWindow);

    // Assert absolute removal of unsafe elements and attributes
    expect(cleaned).not.toContain("<script");
    expect(cleaned).not.toContain("alert(");
    expect(cleaned).not.toContain("onclick");
    expect(cleaned).not.toContain("onload");
    expect(cleaned).not.toContain("onerror");
    expect(cleaned).not.toContain("javascript:");
    expect(cleaned).not.toContain("<iframe");
    expect(cleaned).not.toContain("<form");
    expect(cleaned).not.toContain("<input");
    expect(cleaned).not.toContain("<svg");

    // Assert preservation of legitimate content and attributes
    expect(cleaned).toContain("<h1>Legitimate Article Title</h1>");
    expect(cleaned).toContain("<p>Clickable text</p>");
    expect(cleaned).toContain('href="https://example.com/valid"');
    expect(cleaned).toContain('rel="noopener noreferrer"');
  });

  it("detects files accurately by content rather than file extension", () => {
    const pdfBytes = new Uint8Array([0x25, 0x50, 0x44, 0x46, 0x2d, 0x31, 0x2e, 0x35]);
    expect(detectFileFormat(pdfBytes)).toBe("pdf");

    const htmlBytes = new TextEncoder().encode("<!DOCTYPE html><html><body><h1>Title</h1></body></html>");
    expect(detectFileFormat(htmlBytes)).toBe("html");

    const txtBytes = new TextEncoder().encode("Just a plain text document without markup.");
    expect(detectFileFormat(txtBytes)).toBe("txt");

    const unknownBytes = new Uint8Array([0x00, 0x01, 0x02, 0xff, 0xfe]);
    expect(detectFileFormat(unknownBytes)).toBe("unknown");
  });

  it("imports TXT with UTF-8 BOM and sets Hindi language if Devanagari exceeds 30%", async () => {
    // English TXT with BOM
    const englishText = "\uFEFFThis is an English essay about nature and reading.";
    const engBytes = new TextEncoder().encode(englishText);
    const engResult = await importTxt(engBytes, "nature.txt");
    expect(engResult.meta.language).toBe("en");
    expect(engResult.meta.title).toBe("nature");

    // Hindi TXT
    const hindiText = "हिंदी साहित्य में प्रेमचंद का योगदान अविस्मरणीय और प्रेरणादायक है।";
    const hindiBytes = new TextEncoder().encode(hindiText);
    const hindiResult = await importTxt(hindiBytes, "premchand.txt");
    expect(hindiResult.meta.language).toBe("hi");
  });

  it("imports HTML pages and SingleFile articles cleanly", async () => {
    const htmlPage = `<!DOCTYPE html><html><head><title>Web Standard Story</title></head><body><h1>Web Standard Story</h1><p>Content of the article.</p></body></html>`;
    const bytes = new TextEncoder().encode(htmlPage);
    const result = await importHtml(bytes, "article.html", testWindow);

    expect(result.meta.title).toBe("Web Standard Story");
    expect(result.clean).toBeDefined();
    expect(result.clean?.indexHtml).toContain("Web Standard Story");
    expect(result.reading).toBeDefined();
  });

  it("processes clip payloads into clean index.html and valid EPUB 3 archives", async () => {
    const sampleBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
    const clip = {
      version: 1 as const,
      url: "https://example.com/story",
      title: "Clipped Story",
      byline: "Elena Rostova",
      contentHtml: `<p>First paragraph.</p><img src="https://example.com/story/hero.png" alt="Hero"/>`,
      images: [
        {
          originalUrl: "https://example.com/story/hero.png",
          mime: "image/png",
          base64: sampleBase64
        }
      ]
    };

    const processed = await processClip(clip, testWindow);
    expect(processed.title).toBe("Clipped Story");
    expect(processed.cleanIndexHtml).toContain("Elena Rostova");
    expect(processed.cleanIndexHtml).toContain("images/");
    expect(processed.images.length).toBe(1);
    expect(processed.readingEpub.byteLength).toBeGreaterThan(100);
  });
});
