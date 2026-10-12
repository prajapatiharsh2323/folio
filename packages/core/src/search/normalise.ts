/**
 * Normalisation rules from Section 7:
 * 1. Convert to Unicode NFC
 * 2. Lower-case
 * 3. Remove zero-width joiner (U+200C) and non-joiner (U+200D)
 * 4. Collapse whitespace
 * 5. Fold accents ONLY on Latin letters (NFD, drop combining marks, recompose).
 *    NEVER strip combining marks from Devanagari: vowel signs and virama are part of the word.
 */
export function normalise(text: string): string {
  if (!text) return "";

  // 1. Convert to Unicode NFC
  let s = text.normalize("NFC");

  // 2. Lowercase
  s = s.toLowerCase();

  // 3. Remove ZWJ (U+200D) and ZWNJ (U+200C)
  s = s.replace(/[\u200C\u200D]/g, "");

  // 4. Collapse whitespace
  s = s.replace(/\s+/g, " ").trim();

  // 5. Fold accents ONLY on Latin letters:
  // Match latin characters followed by latin diacritic combining marks (U+0300 to U+036F)
  // Devanagari combining marks are in range U+0900 - U+097F and will NOT be touched.
  s = s.normalize("NFD").replace(/([\u0041-\u005A\u0061-\u007A])[\u0300-\u036F]+/g, "$1").normalize("NFC");

  return s;
}

/**
 * Normalises Hindi inflectional suffixes for search stem matching.
 * Handles standard plurals and case inflections:
 * -यों / -ियों -> -ी
 * -ों / -ें -> ""
 */
export function stemHindi(token: string): string {
  // If token ends with -ियों / -यों (e.g. विद्यार्थियों -> विद्यार्थी)
  if (token.endsWith("ियों") || token.endsWith("यों")) {
    return token.replace(/(?:ि?यों)$/, "ी");
  }
  // If token ends with -ों or -ें (e.g. पुस्तकों -> पुस्तक, पुस्तकें -> पुस्तक)
  if (token.endsWith("ों") || token.endsWith("ें")) {
    return token.replace(/[ोंें]$/, "");
  }
  return token;
}

/**
 * Tokenisation rules from Section 7:
 * Split on /[^\p{L}\p{M}\p{N}]+/u.
 * The \p{M} class keeps Devanagari vowel signs and virama inside words.
 * Do not use \w or a letters-only class.
 */
export function tokenise(text: string): string[] {
  const norm = normalise(text);
  if (!norm) return [];

  // Split on characters that are NOT letters (\p{L}), mark/diacritic (\p{M}), or numbers (\p{N})
  const baseTokens = norm.split(/[^\p{L}\p{M}\p{N}]+/u).filter(Boolean);
  const result: string[] = [];

  for (const t of baseTokens) {
    result.push(t);
    const stem = stemHindi(t);
    if (stem !== t) {
      result.push(stem);
    }
  }

  return result;
}
