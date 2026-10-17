package dev.folio.mobile.core.search

/**
 * Port of the PWA's buildSnippet (packages/core/src/search/index.ts):
 * ~80 chars either side of the first hit (case-insensitive term scan on the
 * raw text), matches wrapped in <mark>, HTML escaped, … prefix/suffix.
 * Summary/no-hit case: first 160 chars + ellipsis.
 */
object SearchSnippet {

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#039;")

    fun build(fullText: String, terms: List<String>): String {
        if (fullText.isEmpty()) return ""
        val normText = fullText.lowercase()

        val validTerms = LinkedHashSet(
            terms.map { it.trim() }.filter { it.isNotEmpty() }
        )

        var firstIndex = -1
        var matchedTerm = ""
        for (term in validTerms) {
            val idx = normText.indexOf(term.lowercase())
            if (idx != -1 && (firstIndex == -1 || idx < firstIndex)) {
                firstIndex = idx
                matchedTerm = term
            }
        }

        if (firstIndex == -1) {
            return escapeHtml(fullText.take(160)) +
                (if (fullText.length > 160) "…" else "")
        }

        val start = maxOf(0, firstIndex - 80)
        val end = minOf(fullText.length, firstIndex + matchedTerm.length + 80)
        val rawSnippet = fullText.substring(start, end)

        // Longest terms first so nested matches merge cleanly (JS contract).
        val sortedTerms = validTerms.sortedByDescending { it.length }
        val intervals = ArrayList<Pair<Int, Int>>()
        if (sortedTerms.isNotEmpty()) {
            // Literal-scan equivalent of the JS combined regex
            // (sortedTerms joined "|", "gi" flag): leftmost non-overlapping
            // matches, longest-first per position. Avoids a per-snippet regex
            // compile, which dominates JVM-side.
            val lowerSnippet = rawSnippet.lowercase()
            var i = 0
            while (i < rawSnippet.length) {
                var matched: Pair<Int, Int>? = null
                for (term in sortedTerms) {
                    val lowerTerm = term.lowercase()
                    val termLen = lowerTerm.length
                    // length-aware cut so surrogate-pair codePoints stay intact
                    if (lowerSnippet.regionMatches(i, lowerTerm, 0, termLen)) {
                        matched = i to i + termLen
                        break
                    }
                }
                if (matched != null) {
                    intervals.add(matched)
                    i = matched.second
                } else {
                    i++
                }
            }
        }

        intervals.sortBy { it.first }
        val merged = ArrayList<Pair<Int, Int>>()
        for (int in intervals) {
            val last = merged.lastOrNull()
            if (last == null) {
                merged.add(int)
            } else if (int.first <= last.second) {
                merged[merged.size - 1] = last.first to maxOf(last.second, int.second)
            } else {
                merged.add(int)
            }
        }

        val sb = StringBuilder()
        var cursor = 0
        for ((intStart, intEnd) in merged) {
            if (intStart > cursor) sb.append(escapeHtml(rawSnippet.substring(cursor, intStart)))
            sb.append("<mark>").append(escapeHtml(rawSnippet.substring(intStart, intEnd))).append("</mark>")
            cursor = intEnd
        }
        if (cursor < rawSnippet.length) sb.append(escapeHtml(rawSnippet.substring(cursor)))

        var snippet = sb.toString()
        if (start > 0) snippet = "…" + snippet
        if (end < fullText.length) snippet += "…"
        return snippet
    }
}
