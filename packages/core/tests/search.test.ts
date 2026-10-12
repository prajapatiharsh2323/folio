import { describe, it, expect } from "vitest";
import { normalise } from "../src/search/normalise.js";
import { SearchIndex, type SearchDocEntry } from "../src/search/index.js";

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
});
