package dev.folio.mobile.core.search

/**
 * Pure helper for rendering PWA-contract snippet HTML ("…text <mark>match</mark>
 * …" with escaped entities) as display text + match ranges. The Compose layer
 * maps [parts] straight into an AnnotatedString.
 */
object SearchUi {

    data class Part(val text: String, val match: Boolean)

    /** Adds up match ranges lengths for quick span math in tests. */
    fun parts(snippetHtml: String): List<Part> {
        val parts = ArrayList<Part>()
        var readingMark = false
        var buffer = StringBuilder()
        fun flush() {
            if (buffer.isNotEmpty()) {
                parts.add(Part(decodeEntities(buffer.toString()), readingMark))
                buffer = StringBuilder()
            }
        }
        var i = 0
        while (i < snippetHtml.length) {
            if (snippetHtml.startsWith("<mark>", i)) {
                flush()
                readingMark = true
                i += 6
            } else if (snippetHtml.startsWith("</mark>", i)) {
                flush()
                readingMark = false
                i += 7
            } else {
                buffer.append(snippetHtml[i])
                i += 1
            }
        }
        flush()
        return parts
    }

    /** The five entities the PWA's escapeHtml emits, in order (& first). */
    fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#039;", "'")
}
