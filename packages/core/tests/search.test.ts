import { describe, it, expect } from "vitest";
import { normalise, stemHindi } from "../src/search/normalise.js";
import { SearchIndex, buildSnippet, type SearchDocEntry } from "../src/search/index.js";

describe("Hindi & Multilingual Search Acceptance Tests (Section 7)", () => {
  it("searching a Hindi word finds every document containing it", () => {
    const index = new SearchIndex();
    index.add({
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "गंगा यात्रा",
      chapterTitle: "शुरुआत",
      author: "राहुल",
      tags: ["यात्रा"],
      text: "गंगा की लहरें अत्यंत पावन और निर्मल हैं।"
    });
    index.add({
      id: "doc2:ch1",
      docId: "doc2",
      chapterId: "ch1",
      title: "हिमालय दर्शन",
      chapterTitle: "पर्वत",
      author: "सुमित्रानंदन",
      tags: ["प्रकृति"],
      text: "पहाड़ों के ऊपर बर्फ और शांति का साम्राज्य है।"
    });

    const results = index.search("गंगा");
    expect(results.length).toBe(1);
    expect(results[0]?.title).toBe("गंगा यात्रा");
  });

  it("prefix matching finds inflected forms in Hindi and English", () => {
    const index = new SearchIndex();
    index.add({
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "कहानी",
      chapterTitle: "भाग १",
      author: "प्रेमचंद",
      tags: [],
      text: "विद्यार्थियों ने अपनी पुस्तकें खोलीं और ध्यान से अध्ययन किया।"
    });

    // Prefix search for विद्यार्थी should match विद्यार्थियों
    const results = index.search("विद्यार्थी");
    expect(results.length).toBe(1);
    expect(results[0]?.docId).toBe("doc1");
  });

  it("matches words typed with and without Zero-Width Joiner (ZWJ U+200D)", () => {
    const withoutZWJ = "नमस्ते";
    const withZWJ = "नमस्\u200Dते"; // Contains ZWJ after halant

    expect(normalise(withoutZWJ)).toBe(normalise(withZWJ));

    const index = new SearchIndex();
    index.add({
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "संवाद",
      chapterTitle: "मिलन",
      author: "लेखक",
      tags: [],
      text: `सभी को ${withZWJ} कहा गया।`
    });

    const searchHits = index.search(withoutZWJ);
    expect(searchHits.length).toBe(1);
  });

  it("nukta forms match after NFC normalisation", () => {
    // Decomposed nukta vs precomposed nukta
    const precomposed = "फ़िल्म"; // U+095E (फ़)
    const decomposed = "फ़िल्म".normalize("NFD"); // U+092B (फ) + U+093C (़)

    expect(normalise(precomposed)).toBe(normalise(decomposed));

    const index = new SearchIndex();
    index.add({
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "सिनेमा",
      chapterTitle: "समीक्षा",
      author: "समीक्षक",
      tags: [],
      text: `यह ${precomposed} बहुत ज्ञानवर्धक है।`
    });

    const hits = index.search(decomposed);
    expect(hits.length).toBe(1);
  });

  it("mixed Hindi and English queries work simultaneously", () => {
    const index = new SearchIndex();
    index.add({
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "Modern Hindi Literature",
      chapterTitle: "Ch 1",
      author: "Sharma",
      tags: ["history"],
      text: "The translation of गीता into modern prose established a new literary standard."
    });

    const hits = index.search("translation गीता");
    expect(hits.length).toBe(1);
  });

  it("matches Latin accented queries with unaccented text without altering Devanagari", () => {
    const index = new SearchIndex();
    index.add({
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "Cafe Culture",
      chapterTitle: "Paris",
      author: "Author",
      tags: [],
      text: "In the famous cafe, readers spent silent mornings."
    });

    // Query with accent: café
    const hits = index.search("café");
    expect(hits.length).toBe(1);

    // Verify Devanagari vowel sign (e.g. 'की' in Hindi) is NOT stripped by Latin accent rule
    const devanagariWord = "की";
    expect(normalise(devanagariWord)).toBe("की");
  });

  it("executes queries across a 500-document library in under 100 ms benchmark", () => {
    const index = new SearchIndex();
    const testEntries: SearchDocEntry[] = [];

    for (let i = 1; i <= 500; i++) {
      testEntries.push({
        id: `doc-${i}:ch-1`,
        docId: `doc-${i}`,
        chapterId: "ch-1",
        title: `Book Title ${i} - ${i % 2 === 0 ? "दर्शन" : "Philosophy"}`,
        chapterTitle: `Chapter 1`,
        author: `Author ${i}`,
        tags: [`tag-${i % 10}`],
        text: `In this chapter ${i}, we explore knowledge, silence, and nature. ${
          i % 5 === 0 ? "यह ज्ञान का अनंत विस्तार है।" : "An inquiry into natural laws."
        }`
      });
    }

    index.addAll(testEntries);

    const startTime = performance.now();
    const hits = index.search("ज्ञान विस्तार");
    const duration = performance.now() - startTime;

    expect(hits.length).toBeGreaterThan(0);
    expect(duration).toBeLessThan(100); // Section 7 benchmark target: < 100 ms
  });

  it("stemHindi correctly handles inflected noun suffixes per decisions.md", () => {
    expect(stemHindi("विद्यार्थियों")).toBe("विद्यार्थी");
    expect(stemHindi("पुस्तकों")).toBe("पुस्तक");
    expect(stemHindi("पुस्तकें")).toBe("पुस्तक");
    expect(stemHindi("लड़कियों")).toBe("लड़की");
  });

  it("buildSnippet escapes HTML entities to prevent HTML injection / XSS", () => {
    const maliciousText = "Hello <script>alert('xss')</script> and <img src=x onerror=alert(1)> in text.";
    const snippet = buildSnippet(maliciousText, ["script", "alert"]);

    // HTML tags in text must be escaped
    expect(snippet).not.toContain("<script>");
    expect(snippet).not.toContain("<img src=");
    expect(snippet).toContain("&lt;<mark>script</mark>&gt;");
    expect(snippet).toContain("&lt;img src=x onerror=");
    // Mark tag must be present for matches
    expect(snippet).toContain("<mark>script</mark>");
    expect(snippet).toContain("<mark>alert</mark>");
  });

  it("buildSnippet correctly highlights multi-term queries without garbling markup or entities", () => {
    const text1 = "The market is a mark of trade.";
    const snippet1 = buildSnippet(text1, ["market", "ar"]);
    expect(snippet1).toContain("<mark>market</mark>");
    expect(snippet1).toContain("m<mark>ar</mark>k");
    expect(snippet1).not.toContain("<m<mark>");

    const text2 = "Fish & chips with an amp amplifier.";
    const snippet2 = buildSnippet(text2, ["amp"]);
    expect(snippet2).toContain("&amp;");
    expect(snippet2).not.toContain("<mark>&amp;</mark>");
    expect(snippet2).toContain("<mark>amp</mark>");
  });

  it("gracefully handles duplicate IDs in add and addAll without throwing", () => {
    const index = new SearchIndex();
    const entry1: SearchDocEntry = {
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "Doc One",
      chapterTitle: "Chapter 1",
      author: "Author",
      tags: [],
      text: "First version of chapter 1"
    };
    const entry1Updated: SearchDocEntry = {
      id: "doc1:ch1",
      docId: "doc1",
      chapterId: "ch1",
      title: "Doc One",
      chapterTitle: "Chapter 1",
      author: "Author",
      tags: [],
      text: "Updated version of chapter 1"
    };

    // Should not throw on re-adding same ID
    expect(() => index.add(entry1)).not.toThrow();
    expect(() => index.add(entry1Updated)).not.toThrow();

    // Should not throw when addAll contains duplicate IDs within the same batch or matching existing index
    const batchWithDuplicates: SearchDocEntry[] = [
      {
        id: "doc2:ch-2",
        docId: "doc2",
        chapterId: "ch-2",
        title: "Doc Two",
        chapterTitle: "Chapter 2",
        author: "Author",
        tags: [],
        text: "Initial text for ch-2"
      },
      {
        id: "doc2:ch-2",
        docId: "doc2",
        chapterId: "ch-2",
        title: "Doc Two",
        chapterTitle: "Chapter 2",
        author: "Author",
        tags: [],
        text: "Duplicate text for ch-2 in same batch"
      },
      {
        id: "doc1:ch1",
        docId: "doc1",
        chapterId: "ch1",
        title: "Doc One",
        chapterTitle: "Chapter 1",
        author: "Author",
        tags: [],
        text: "Batch re-indexing of existing doc1"
      }
    ];

    expect(() => index.addAll(batchWithDuplicates)).not.toThrow();
    const results = index.search("Duplicate");
    expect(results.length).toBe(1);
    expect(results[0]?.docId).toBe("doc2");
  });

  it("persists and reloads search index via JSON without inflating search.json with raw texts", () => {
    const original = new SearchIndex();
    const entry = {
      id: "book-1:ch-1",
      docId: "book-1",
      chapterId: "ch-1",
      title: "The Solitary Reader",
      chapterTitle: "Chapter 1",
      author: "Arthur Pendelton",
      tags: ["novel"],
      text: "The sun slanted across the cedar bookshelves of the library in warm autumn light."
    };
    original.add(entry);

    const serialized = original.toJSON();
    expect(typeof serialized).toBe("string");
    // Raw full texts must not be duplicated into the search index file
    expect(serialized).not.toContain("The sun slanted across the cedar");

    const reloaded = SearchIndex.loadJSON(serialized);
    const rawTexts = new Map([["book-1:ch-1", entry.text]]);
    const results = reloaded.search("cedar", rawTexts);
    expect(results.length).toBe(1);
    expect(results[0]?.docId).toBe("book-1");
    expect(results[0]?.hits[0]?.snippet).toContain("<mark>cedar</mark>");
  });
});

