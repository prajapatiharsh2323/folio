import * as fflate from "fflate";

export type DetectedFormat = "epub" | "pdf" | "html" | "txt" | "unknown";

/**
 * Section 6 File detection rules:
 * Detect by content, never by extension:
 * - ZIP containing a mimetype entry of application/epub+zip is EPUB
 * - Bytes starting with %PDF- are PDF
 * - Text that parses as HTML (<html, <!doctype, <body) is HTML
 * - Anything else valid UTF-8 is TXT
 * - Unknown binary is rejected as unknown
 */
export function detectFileFormat(bytes: Uint8Array): DetectedFormat {
  if (bytes.byteLength === 0) return "unknown";

  // Check PDF signature: %PDF- (0x25 0x50 0x44 0x46 0x2D)
  if (
    bytes.byteLength >= 5 &&
    bytes[0] === 0x25 &&
    bytes[1] === 0x50 &&
    bytes[2] === 0x44 &&
    bytes[3] === 0x46 &&
    bytes[4] === 0x2d
  ) {
    return "pdf";
  }

  // Check ZIP / EPUB
  // ZIP header is PK\x03\x04 (0x50 0x4B 0x03 0x04)
  if (
    bytes.byteLength >= 4 &&
    bytes[0] === 0x50 &&
    bytes[1] === 0x4b &&
    bytes[2] === 0x03 &&
    bytes[3] === 0x04
  ) {
    try {
      const unzipped = fflate.unzipSync(bytes, {
        filter(file) {
          return file.name === "mimetype";
        }
      });
      if (unzipped["mimetype"]) {
        const mimeStr = fflate.strFromU8(unzipped["mimetype"]).trim();
        if (mimeStr === "application/epub+zip") {
          return "epub";
        }
      }
    } catch {
      // Not a valid EPUB zip
    }
  }

  // Check Text / HTML
  try {
    const decoder = new TextDecoder("utf-8", { fatal: true });
    // Decode head up to first 4KB to check HTML declarations
    const sample = decoder.decode(bytes.subarray(0, Math.min(bytes.length, 4096))).toLowerCase().trim();

    if (
      sample.includes("<html") ||
      sample.includes("<!doctype html") ||
      sample.includes("<body") ||
      sample.includes("<head")
    ) {
      return "html";
    }

    // Try full decode to ensure valid UTF-8
    decoder.decode(bytes);
    return "txt";
  } catch {
    return "unknown";
  }
}
