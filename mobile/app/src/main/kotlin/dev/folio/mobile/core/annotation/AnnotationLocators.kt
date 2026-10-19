package dev.folio.mobile.core.annotation

import dev.folio.mobile.core.reader.ProgressBridge

/**
 * Resolves a schema-1 [dev.folio.mobile.core.model.Annotation] `location` back to
 * a spine index in the publication's reading order — the link needed to rebuild
 * a highlight Locator for persisting rendering (R3).
 *
 * BYTE-COMPAT CONTRACT (apps/web/src/reader/epub-engine.ts): "quote+context is
 * authoritative" — Readium 3.0.0's WebView decorator anchors highlight ranges
 * from `locator.text` (highlight/before/after) using Hypothesis' TextQuoteAnchor
 * (verified in toolkit 3.0.0 `assets/_scripts/src/utils.js rangeFromLocator`),
 * so NO CFI parser is required. The spine index is only needed to route the
 * decoration to the right resource (decoration routing is keyed by
 * `decoration.locator.href` — `DecorableNavigator.changesByHref`).
 *
 * `location` carries either an `epubcfi(...)` string (spine refs → index via
 * [ProgressBridge.cfiSpineIndex]) or a chapter-href fallback written by
 * [ProgressBridge.locationString].
 *
 * Primitive-arg core is JVM-testable; Locator construction lives in the reader
 * layer (Android runtime — see ProgressBridge KDoc).
 */
object AnnotationLocators {

    /**
     * Index in [readingOrderHrefs] targeted by [location], or null when the
     * location cannot be trusted to target a reading-order resource.
     *
     * Matching rules (in order):
     * 1. `epubcfi(...)` → spine index decoded from the CFI base ref,
     * 2. exact href match,
     * 3. suffix match (PWA may store hrefs with a leading "./" or with slug dirs).
     */
    fun anchorIndex(readingOrderHrefs: List<String>, location: String?): Int? {
        if (location.isNullOrEmpty()) return null
        if (location.startsWith(ProgressBridge.CFI_PREFIX)) {
            val idx = ProgressBridge.cfiSpineIndex(location) ?: return null
            return idx.takeIf { it in readingOrderHrefs.indices }
        }
        readingOrderHrefs.indexOfFirst { it == location }
            .takeIf { it >= 0 }
            ?.let { return it }
        val stripped = location.removePrefix("/").removePrefix("./")
        readingOrderHrefs.indexOfFirst { it.removePrefix("/").removePrefix("./") == stripped }
            .takeIf { it >= 0 }?.let { return it }
        readingOrderHrefs.indexOfFirst { it.endsWith(location) || location.endsWith(it) }
            .takeIf { it >= 0 }?.let { return it }
        return null
    }
}
