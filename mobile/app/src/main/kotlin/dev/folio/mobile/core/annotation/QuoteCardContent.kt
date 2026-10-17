package dev.folio.mobile.core.annotation

/**
 * Pure quote-card content (R3 deliverable): what a shareable quote-card image
 * shows, derived only from schema-1 types. JVM-testable; the Android Bitmap
 * painter in the reader layer consumes [QuoteCardLayout].
 */
object QuoteCardContent {

    data class QuoteCardLayout(
        val quoteLines: List<String>,
        val noteLines: List<String>,
        val bookTitle: String?,
        val author: String?,
        val colorArgb: Long
    )

    /** Public share clamps (PWA quote-card parity targets). */
    const val MAX_QUOTE_CHARS = 280
    const val MAX_NOTE_CHARS = 160
    const val QUOTE_LINE_CHARS = 38
    const val NOTE_LINE_CHARS = 42

    fun of(
        quote: String,
        note: String?,
        bookTitle: String?,
        author: String?,
        colorHex: Long
    ): QuoteCardLayout = QuoteCardLayout(
        quoteLines = clampAndWrap(quote.trim(), MAX_QUOTE_CHARS, QUOTE_LINE_CHARS),
        noteLines = note?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { clampAndWrap(it, MAX_NOTE_CHARS, NOTE_LINE_CHARS) }
            ?: emptyList(),
        bookTitle = bookTitle?.trim()?.takeIf { it.isNotEmpty() },
        author = author?.trim()?.takeIf { it.isNotEmpty() },
        colorArgb = colorHex
    )

    /** Clamps to [maxChars] at a word boundary, then wraps into [lineChars] lines. */
    fun clampAndWrap(text: String, maxChars: Int, lineChars: Int): List<String> {
        val clamped = if (text.length <= maxChars) text else {
            val cut = text.substring(0, maxChars)
            val lastSpace = cut.lastIndexOf(' ')
            if (lastSpace > maxChars / 2) cut.substring(0, lastSpace) + "…" else cut + "…"
        }
        return clamped.lineSequence()
            .flatMap { wrapLine(it, lineChars) }
            .toList()
    }

    private fun wrapLine(line: String, lineChars: Int): List<String> {
        if (line.length <= lineChars) return listOf(line)
        val out = mutableListOf<String>()
        var rest = line
        while (rest.length > lineChars) {
            var boundary = rest.lastIndexOf(' ', lineChars)
            if (boundary <= 0) boundary = lineChars
            out += rest.substring(0, boundary).trimEnd()
            rest = rest.substring(boundary).trimStart()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
