import { describe, it, expect } from "vitest";
import { JSDOM } from "jsdom";
import { sanitizeSectionDoc, sanitizeSerializedHtml } from "../src/import/epub-sanitize.js";

describe("EPUB Security & Sandboxed Iframe Sanitization Tests", () => {
  describe("sanitizeSectionDoc (DOM Level)", () => {
    it("completely removes script and noscript elements including namespaced tags", () => {
      const dom = new JSDOM(`
        <!DOCTYPE html>
        <html>
        <head>
          <title>Test Chapter</title>
          <script type="text/javascript" src="tracking.js"></script>
          <script>console.log("inline script execution attempt");</script>
          <noscript><p>Please enable javascript</p></noscript>
          <style>body { color: #333; }</style>
        </head>
        <body>
          <h1>Chapter 1: The Sanctuary</h1>
          <p>Valid text content that must remain untouched.</p>
          <svg width="100" height="100">
            <script>alert("svg script");</script>
            <circle cx="50" cy="50" r="40" fill="green" />
          </svg>
        </body>
        </html>
      `, { contentType: "application/xhtml+xml" });

      const doc = dom.window.document;

      // Sanitize the document
      sanitizeSectionDoc(doc);

      // Verify all script and noscript elements are removed
      expect(doc.querySelectorAll("script").length).toBe(0);
      expect(doc.querySelectorAll("noscript").length).toBe(0);
      expect(doc.getElementsByTagName("script").length).toBe(0);

      // Verify legitimate styles and content are preserved
      expect(doc.querySelector("h1")?.textContent).toBe("Chapter 1: The Sanctuary");
      expect(doc.querySelector("p")?.textContent).toBe("Valid text content that must remain untouched.");
      expect(doc.querySelector("circle")?.getAttribute("fill")).toBe("green");
      expect(doc.querySelector("style")?.textContent).toContain("body { color: #333; }");
    });

    it("strips all inline on* event handler attributes and disarms javascript: URLs", () => {
      const dom = new JSDOM(`
        <!DOCTYPE html>
        <html>
        <body onload="window.maliciousCode()" onresize="trackResize()">
          <h2 onclick="handleClick()">Section Header</h2>
          <img src="images/safe.jpg" alt="Safe" onload="reportLoaded()" onerror="stealData()" />
          <a href="javascript:alert('xss')" id="bad-link">Dangerous link</a>
          <a href="chapter2.xhtml#anchor" id="good-link">Valid chapter anchor</a>
          <div onmouseover="peek()" onfocus="doFocus()">Interactive area</div>
        </body>
        </html>
      `, { contentType: "application/xhtml+xml" });

      const doc = dom.window.document;

      sanitizeSectionDoc(doc);

      const body = doc.querySelector("body");
      expect(body).not.toBeNull();
      expect(body?.hasAttribute("onload")).toBe(false);
      expect(body?.hasAttribute("onresize")).toBe(false);

      const h2 = doc.querySelector("h2");
      expect(h2?.hasAttribute("onclick")).toBe(false);

      const img = doc.querySelector("img");
      expect(img?.getAttribute("src")).toBe("images/safe.jpg");
      expect(img?.hasAttribute("onload")).toBe(false);
      expect(img?.hasAttribute("onerror")).toBe(false);

      const badLink = doc.getElementById("bad-link");
      expect(badLink?.hasAttribute("href")).toBe(false);

      const goodLink = doc.getElementById("good-link");
      expect(goodLink?.getAttribute("href")).toBe("chapter2.xhtml#anchor");

      const div = doc.querySelector("div");
      expect(div?.hasAttribute("onmouseover")).toBe(false);
      expect(div?.hasAttribute("onfocus")).toBe(false);
    });
  });

  describe("sanitizeSerializedHtml (Serialization Level)", () => {
    it("regex-cleans residual script tags, self-closing scripts, and noscripts from serialized strings", () => {
      const rawSerialized = `
        <html xmlns="http://www.w3.org/1999/xhtml">
          <head>
            <script type="text/javascript">
              window.untrustedGlobal = "compromised";
            </script>
            <script src="external-payload.js" />
            <noscript>Fall back content</noscript>
            <link rel="stylesheet" href="style.css" />
          </head>
          <body onload="alert('body onload')">
            <h1>Safe Heading</h1>
            <p onclick="alert('p click')">Legitimate text</p>
            <a href="javascript:doSomething()">Attack Vector</a>
            <a href="https://example.com/safe">Safe URL</a>
          </body>
        </html>
      `;

      const sectionObj = { output: rawSerialized };
      const cleaned = sanitizeSerializedHtml(rawSerialized, sectionObj);

      // Verify stripped from returned string
      expect(cleaned).not.toContain("<script");
      expect(cleaned).not.toContain("</script>");
      expect(cleaned).not.toContain("<noscript");
      expect(cleaned).not.toContain("window.untrustedGlobal");
      expect(cleaned).not.toContain("onload=");
      expect(cleaned).not.toContain("onclick=");
      expect(cleaned).not.toContain("javascript:");

      // Verify legitimate markup remains intact
      expect(cleaned).toContain("<h1>Safe Heading</h1>");
      expect(cleaned).toContain("Legitimate text");
      expect(cleaned).toContain('<a href="https://example.com/safe">Safe URL</a>');
      expect(cleaned).toContain('<link rel="stylesheet" href="style.css" />');

      // Verify mutated on section.output
      expect(sectionObj.output).toBe(cleaned);
    });

    it("strips namespaced scripts, self-closing tags, and meta refresh headers", () => {
      const xmlPayload = `
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:xhtml="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head>
            <meta http-equiv="refresh" content="0;url=http://malicious.com" />
            <xhtml:script type="text/javascript">stealCookies();</xhtml:script>
            <epub:script src="epub-exploit.js"/>
            <link rel="stylesheet" href="main.css" />
          </head>
          <body onpageshow="track()" data-testid="body">
            <button formaction="javascript:pwn()">Submit</button>
            <p>Protected Content</p>
          </body>
        </html>
      `;

      const cleaned = sanitizeSerializedHtml(xmlPayload);
      expect(cleaned).not.toContain("xhtml:script");
      expect(cleaned).not.toContain("epub:script");
      expect(cleaned).not.toContain("stealCookies");
      expect(cleaned).not.toContain("refresh");
      expect(cleaned).not.toContain("onpageshow");
      expect(cleaned).not.toContain("javascript:pwn()");
      expect(cleaned).toContain("<p>Protected Content</p>");
      expect(cleaned).toContain('href="main.css"');
    });
  });
});
