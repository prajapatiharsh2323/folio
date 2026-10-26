package dev.folio.mobile.core.reader

import org.readium.r2.shared.publication.Locator

/**
 * In-book search bridge (audit EPUB-015). The navigator's UI list and the
 * Readium search plumbing meet here:
 *
 * - Readium 3.0.0 exposes search through the publication's `SearchService`
 *   (`StringSearchService`, auto-registered by `EpubParser` — verified in the
 *   streamer bytecode), yielding `Locator`s per hit.
 * - Every hit locator carries a `text.highlight` anchor, and the reflowable
 *   page's `loadLocator` prefers that anchor (`R2WebView.scrollToLocator`) —
 *   so `navigator.go(hitLocator)` lands on the matched text, not just the
 *   chapter.
 *
 * This object holds the JVM-testable formatting/guard logic; the Locator
 * reading side stays a thin overload (same split as [ProgressBridge]: building
 * Readium `Locator`/`Url` values needs the Android runtime, so unit tests only
 * exercise the primitive overloads).
 */
object ReaderSearch {

    /** Queries shorter than this are noise (lib-search parity: min 2 chars). */
    const val MIN_QUERY_LENGTH = 2

    /** Hard cap on surfaced hits — keeps the Compose list bounded on sagas. */
    const val MAX_HITS = 200

    /** Rendered snippet width cap (chars). */
    const val SNIPPET_MAX = 160

    /** One row of the reader search sheet. */
    data class Hit(
        /** One-line collapsed excerpt around the match (css-stripped). */
        val snippet: String,
        /** Chapter/page label under which the hit appeared (best effort). */
        val sectionTitle: String?,
        /** Match span inside [snippet] — the UI emphasizes exactly this text. */
        val matchStart: Int = -1,
        val matchEnd: Int = -1
    ) {
        val hasMatch: Boolean get() = matchStart >= 0 && matchEnd > matchStart
    }

    /** Excerpt + highlight span, built so the match itself is never truncated away. */
    data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

    /** Only submit queries long enough to be useful. */
    fun isRunnableQuery(raw: String): Boolean = raw.trim().length >= MIN_QUERY_LENGTH

    /**
     * Collapses the locator's text context (before / highlight / after) into a
     * single-line snippet: whitespace run-length reduced, hard-capped with an
     * ellipsis. Empty with no context at all.
     */
    fun snippet(before: String?, highlight: String?, after: String?): String {
        val parts = listOf(before, highlight, after)
            .mapNotNull { part ->
                part?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }
            }
        if (parts.isEmpty()) return ""
        var joined = parts.joinToString(" ")
        if (joined.length > SNIPPET_MAX) {
            joined = joined.take(SNIPPET_MAX) + "…"
        }
        return joined
    }

    /**
     * Match-aware excerpt (audit P1-13). The old `snippet()` windowed from the
     * start of `before`, which could cut the actual query out of long context.
     * This centers the window on the match, keeping leading context when it
     * fits and pointing the UI at the exact span to emphasize.
     */
    fun buildSnippet(before: String?, highlight: String?, after: String?): Snippet? {
        fun clean(value: String?): String =
            value?.replace(WHITESPACE, " ")?.trim().orEmpty()

        val beforeText = clean(before)
        val afterText = clean(after)
        var matched = clean(highlight)
        if (matched.isEmpty()) return null

        // If the match itself is longer than the budget, truncate it (the span
        // still points at the visible portion).
        if (matched.length > SNIPPET_MAX - 8) {
            matched = matched.take(SNIPPET_MAX - 8)
        }

        // Reserve room for the match itself, the two joining spaces and up to
        // two ellipses so the finished excerpt never exceeds SNIPPET_MAX (+1 for
        // the trailing ellipsis).
        val budgetForContext = (SNIPPET_MAX - matched.length - 4).coerceAtLeast(0)
        var beforePart = beforeText
        if (beforePart.length > budgetForContext) {
            beforePart = if (budgetForContext > 1) {
                "…" + beforePart.takeLast(budgetForContext - 1)
            } else {
                ""
            }
        }
        val remaining = (budgetForContext - beforePart.length - 1).coerceAtLeast(0)
        var afterPart = afterText.take(remaining)
        if (afterPart.length < afterText.length) {
            afterPart += "…"
        }

        val text = listOf(beforePart, matched, afterPart)
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        // Compute the span from the known layout (indexOf would find an earlier
        // duplicate of the query inside the leading context).
        val start = if (beforePart.isEmpty()) 0 else beforePart.length + 1
        if (!text.regionMatches(start, matched, 0, matched.length)) return null
        return Snippet(text, start, start + matched.length)
    }

    /**
     * Best-effort hit label: Readium locators usually carry the chapter title.
     * When absent the UI shows no label (or its own "Section" fallback) — a raw
     * resource file name is never surfaced (audit P1-13).
     */
    fun sectionTitle(locatorTitle: String?, href: String): String? =
        locatorTitle?.trim()?.takeIf { it.isNotEmpty() }

    // ---- Locator-side conveniences (Android runtime only; keep thin) ----

    /** Builds the sheet row for a search-service locator. */
    fun hitFromLocator(locator: Locator): Hit {
        val built = buildSnippet(
            before = locator.text.before,
            highlight = locator.text.highlight,
            after = locator.text.after
        )
        return if (built != null) {
            Hit(
                snippet = built.text,
                sectionTitle = sectionTitle(locator.title, locator.href.toString()),
                matchStart = built.matchStart,
                matchEnd = built.matchEnd
            )
        } else {
            Hit(
                snippet = snippet(
                    before = locator.text.before,
                    highlight = locator.text.highlight,
                    after = locator.text.after
                ),
                sectionTitle = sectionTitle(locator.title, locator.href.toString())
            )
        }
    }

    private val WHITESPACE = Regex("""\s+""")
}
