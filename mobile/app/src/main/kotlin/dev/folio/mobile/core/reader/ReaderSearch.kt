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
        val sectionTitle: String?
    )

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
     * Best-effort hit label: Readium locators usually carry the chapter title;
     * when absent, fall back to the resource file name (never a raw base path
     * or fragment).
     */
    fun sectionTitle(locatorTitle: String?, href: String): String? {
        locatorTitle?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val segment = href.substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .trim()
        return segment.takeIf { it.isNotEmpty() }
    }

    // ---- Locator-side conveniences (Android runtime only; keep thin) ----

    /** Builds the sheet row for a search-service locator. */
    fun hitFromLocator(locator: Locator): Hit = Hit(
        snippet = snippet(
            before = locator.text.before,
            highlight = locator.text.highlight,
            after = locator.text.after
        ),
        sectionTitle = sectionTitle(locator.title, locator.href.toString())
    )

    private val WHITESPACE = Regex("""\s+""")
}
