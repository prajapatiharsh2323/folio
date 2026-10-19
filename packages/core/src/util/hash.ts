/**
 * Computes SHA-256 hash string (hexadecimal) from a Uint8Array.
 * Uses Web Crypto API (crypto.subtle.digest).
 */
export async function sha256Hex(data: Uint8Array): Promise<string> {
  const hashBuffer = await crypto.subtle.digest("SHA-256", data as unknown as ArrayBuffer);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Creates a slug from date and title according to Section 4:
 * `<slug>` is `YYYY-MM-DD-title-words-xxxx`: lower-case ASCII (transliterate or fall back to `doc`),
 * at most 60 characters, ending with 4 random hex characters.
 */
export function createDocSlug(title: string, date = new Date()): string {
  const yyyy = date.getUTCFullYear();
  const mm = String(date.getUTCMonth() + 1).padStart(2, "0");
  const dd = String(date.getUTCDate()).padStart(2, "0");
  const datePrefix = `${yyyy}-${mm}-${dd}`;

  // Normalize and convert title to lowercase ASCII
  let cleanTitle = title
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "") // strip latin diacritics
    .replace(/[^a-z0-9]+/g, "-") // replace non-alphanumeric with dash
    .replace(/^-+|-+$/g, "");

  if (!cleanTitle) {
    cleanTitle = "doc";
  }

  // 4 random hex characters
  const randomHex = Math.floor(Math.random() * 0x10000)
    .toString(16)
    .padStart(4, "0");

  // Format: YYYY-MM-DD-title-xxxx (max 60 chars)
  // datePrefix is 10 chars + 1 dash + 4 hex + 1 dash = 16 chars overhead
  const maxTitleLen = 60 - 16;
  if (cleanTitle.length > maxTitleLen) {
    cleanTitle = cleanTitle.substring(0, maxTitleLen).replace(/-+$/, "");
  }

  return `${datePrefix}-${cleanTitle}-${randomHex}`;
}
