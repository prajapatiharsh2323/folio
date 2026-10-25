package dev.folio.mobile.core.dictionary

import java.text.Normalizer
import java.util.Locale

/**
 * Pure selection-normalization (D1). Mirrors `tools/build_dict.py` key rules:
 *
 * - trim + strip *surrounding* punctuation; internal apostrophes/hyphens that
 *   belong to a word survive (`don't`, `well-being`).
 * - NFKC + case fold keyed on [Locale.ENGLISH] (the dataset is English).
 * - collapse internal whitespace to single space (the converter's key_norm).
 * - `relaxedKey` additionally removes `'`/`-`/space so a stored `well being`
 *   answers a selected `well-being`; relevant-only tier — the primary key
 *   always runs first.
 *
 * Guards (failures spelled out for the UI): blank/whitespace-only,
 * punctuation-only, and arbitrary long/paragraph-length selections are not a
 * lookup; a phrase cap of 3 words covers short expressions only (`give up`).
 */
object DictionaryNormalizer {

    /** Max words accepted as a meaningful lookup query. */
    const val MAX_PHRASE_WORDS = 3

    /** Max characters accepted before declaring the selection too long. */
    const val MAX_INPUT_CHARS = 64

    private const val QUOTES = "\u0022\u0027\u2018\u2019\u201C\u201D\u00AB\u00BB"

    /** "don't." / "「run」" or a paragraph → null with a UI-facing reason. */
    fun normalize(raw: String?): DictionaryQuery? {
        var s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        if (s.length > MAX_INPUT_CHARS) return null
        s = s.trim { it in QUOTES || Character.isWhitespace(it) }
        // Strip trailing/leading punctuation but keep word-internal marks.
        s = s.trim { !it.isLetterOrDigit() && it !in "'-" }
        if (s.isEmpty()) return null
        s = s.replace(Regex("\\s+"), " ")
        val words = s.split(' ')
        if (words.count { it.isNotBlank() } > MAX_PHRASE_WORDS) return null
        if (s.none { it.isLetterOrDigit() }) return null
        val folded = s.lowercase(Locale.ENGLISH)
        if (folded.isEmpty()) return null
        return DictionaryQuery(raw = raw.orEmpty(), displayKey = folded)
    }

    fun relaxedKey(query: DictionaryQuery): String =
        query.displayKey.replace("\'", "").replace("-", "").replace(" ", "")

    /**
     * Short display form for the sheet header: the selection as captured,
     * with surrounding punctuation removed, original casing preserved.
     */
    fun cleanDisplay(raw: String?): String {
        var s = raw?.trim().orEmpty()
        s = s.trim { !it.isLetterOrDigit() && it !in "'-" }
        return s.ifEmpty { raw?.trim().orEmpty() }
    }
}
