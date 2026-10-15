import { describe, it, expect } from "vitest";
import { HARMONIC_HUES, getBookHue } from "../../../apps/web/src/components/BookCard.js";
import type { DocMeta } from "../src/types.js";

describe("BookCard Harmonic Palette & Hue Calculation", () => {
  const dummyDoc: DocMeta = {
    schema: 1,
    id: "doc-12345",
    slug: "the-solitary-reader",
    title: "The Solitary Reader",
    author: "M. Ellison",
    kind: "book",
    sourceFormat: "epub",
    capturedAt: new Date().toISOString(),
    importedAt: new Date().toISOString(),
    contentHash: "dummyhash",
    searchable: true,
    tags: ["fiction"],
    collectionIds: [],
    files: {},
    chapters: []
  };

  it("contains all 8 curated harmonic hues", () => {
    expect(HARMONIC_HUES.length).toBe(8);
    const hues = HARMONIC_HUES.map((h) => h.hue);
    expect(hues).toEqual([135, 195, 250, 285, 330, 10, 50, 90]);
  });

  it("deterministically calculates the same hue for a given document title and id", () => {
    const hue1 = getBookHue(dummyDoc);
    const hue2 = getBookHue(dummyDoc);
    expect(hue1).toBe(hue2);
    expect(HARMONIC_HUES.some((h) => h.hue === hue1)).toBe(true);
  });

  it("respects customHue override when provided", () => {
    const customHue = 330;
    const hue = getBookHue(dummyDoc, customHue);
    expect(hue).toBe(330);
  });

  it("handles Hindi and multilingual document titles gracefully", () => {
    const hindiDoc: DocMeta = {
      ...dummyDoc,
      id: "doc-hindi-1",
      title: "निर्मल धारा",
      author: "प्रेमचंद",
      language: "hi"
    };
    const hue = getBookHue(hindiDoc);
    expect(typeof hue).toBe("number");
    expect(HARMONIC_HUES.some((h) => h.hue === hue)).toBe(true);
  });
});
