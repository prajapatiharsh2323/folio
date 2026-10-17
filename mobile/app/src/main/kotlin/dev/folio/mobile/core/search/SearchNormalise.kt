package dev.folio.mobile.core.search

/**
 * Pure port of the PWA search normalisation (packages/core/src/search/normalise.ts):
 * tokens + Hindi stems. JVM-testable, no Android imports.
 *
 * Rules (spec §7), kept 1:1 with the TypeScript contract:
 * 1. Unicode NFC
 * 2. lower-case
 * 3. strip ZWJ (U+200D) / ZWNJ (U+200C)
 * 4. collapse whitespace
 * 5. fold accents on LATIN letters only (NFD, drop U+0300–U+036F, recompose).
 *    NEVER strip Devanagari combining marks (they are part of the word).
 */
object SearchNormalise {

    // Kotlin Regex supports the same Unicode classes via \p{...} with UNICODE_CASE.
    private val NON_WORD = Regex("[^\\p{L}\\p{M}\\p{N}]+")
    private val LATIN_FOLD = Regex("([\\u0041-\\u005A\\u0061-\\u007A])[\\u0300-\\u036F]+")

    fun normalise(text: String): String {
        if (text.isEmpty()) return ""
        var s = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFC)
        s = s.lowercase()
        s = s.replace("\u200C", "").replace("\u200D", "")
        s = s.replace(Regex("\\s+"), " ").trim()
        // Latin-only fold (NFD round-trip) applies ONLY if the string can
        // contain a Latin base + combining mark (precomposed accented Latin or
        // explicit U+0300–U+036F marks). Otherwise the fold is a no-op and can
        // be skipped — same output, much faster for Devanagari/ASCII corpora.
        if (canLatinFold(s)) {
            s = java.text.Normalizer.normalize(
                LATIN_FOLD.replace(
                    java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD),
                    "$1"
                ),
                java.text.Normalizer.Form.NFC
            )
        }
        return s
    }

    /**
     * Fast-path guard: fold could fire only when a Latin base could end up
     * followed by a U+0300–U+036F mark after NFD. That requires either a
     * precomposed accented Latin char (which NFC regenerates from decomposed
     * input) or an explicit mark char in the (lower-cased) string.
     */
    private fun canLatinFold(s: String): Boolean {
        for (c in s) {
            val code = c.code
            if (code in 0x0300..0x036F) return true
            // Latin-1 accented supplement, Latin Ext-A/B, IPA Extensions,
            // Spacing Modifiers, Latin Extended Additional
            if (code in 0x00C0..0x00FF || code in 0x0100..0x024F ||
                code in 0x1E00..0x1EFF
            ) return true
        }
        return false
    }

    /**
     * Hindi inflectional suffixes (decisions.md via normalise.ts):
     * -यों / -ियों → -ी, -ों / -ें → "" (e.g. विद्यार्थियों → विद्यार्थी,
     * पुस्तकों/पुस्तकें → पुस्तक, लड़कियों → लड़की).
     */
    fun stemHindi(token: String): String = when {
        token.endsWith("ियों") || token.endsWith("यों") ->
            token.replace(Regex("(?:ि?यों)$"), "ी")
        token.endsWith("ों") || token.endsWith("ें") ->
            token.replace(Regex("(?:ों|ें)$"), "")
        else -> token
    }

    /**
     * Tokenisation: split on anything that is NOT [\p{L}\p{M}\p{N}], drop empties,
     * and append the Hindi stem of each token that has one (BOTH forms are indexed —
     * "विद्यार्थियों" and its stem "विद्यार्थी" become separate index terms).
     * The result is always already normalised (tokenise calls normalise first).
     */
    fun tokenise(text: String): List<String> {
        val norm = normalise(text)
        if (norm.isEmpty()) return emptyList()
        val base = norm.split(NON_WORD).filter { it.isNotEmpty() }
        val out = ArrayList<String>(base.size * 2)
        for (t in base) {
            out.add(t)
            val stem = stemHindi(t)
            if (stem != t) out.add(stem)
        }
        return out
    }
}
