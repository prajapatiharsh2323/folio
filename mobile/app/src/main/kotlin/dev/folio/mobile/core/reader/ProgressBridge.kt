package dev.folio.mobile.core.reader

import dev.folio.mobile.core.model.Progress
import org.readium.r2.shared.publication.Locator

/**
 * Maps Readium Locators onto the schema-1 Progress model.
 *
 * BYTE-COMPAT CONTRACT (see .docs/MOBILE-TRACKING.md item 9): the PWA stores
 * epub.js `epubcfi(...)` strings in `Progress.location`. Mobile writes the CFI
 * string from the Readium Locator's fragments (falling back to the chapter href
 * when no CFI is available). `percent` is the total publication progression
 * (0..1). Never store the full Readium Locator JSON here — it would break
 * compatibility with the PWA's progress files.
 *
 * The primitive-based overloads are the JVM-testable core; constructing Readium
 * `Locator`/`Url` values requires the Android runtime (`android.net.Uri`), so the
 * Locator overloads must stay thin.
 */
object ProgressBridge {

    const val CFI_PREFIX = "epubcfi("

    /** First CFI among the locator fragments, if any. */
    fun extractedCfi(fragments: List<String>): String? =
        fragments.firstOrNull { it.startsWith(CFI_PREFIX) }

    /**
     * progress.location contract (byte-compat with the PWA): CFI string for
     * reflowable EPUB, else chapter href fallback.
     */
    fun locationString(fragments: List<String>, href: String): String =
        extractedCfi(fragments) ?: href

    /** Total progression across the whole publication (0..1). */
    fun locationPercent(totalProgression: Double?): Double = totalProgression ?: 0.0

    fun buildProgress(
        docId: String,
        fragments: List<String>,
        href: String,
        totalProgression: Double?,
        updatedAt: String
    ): Progress = Progress(
        docId = docId,
        location = locationString(fragments, href),
        percent = locationPercent(totalProgression),
        updatedAt = updatedAt
    )

    // ---- Locate-object conveniences (Android runtime only; keep thin) ----

    fun extractedCfi(locator: Locator): String? = extractedCfi(locator.locations.fragments)

    fun locationString(locator: Locator): String =
        locationString(locator.locations.fragments, locator.href.toString())

    fun totalPercent(locator: Locator): Double =
        locationPercent(locator.locations.totalProgression)

    fun buildProgress(docId: String, locator: Locator, updatedAt: String): Progress =
        buildProgress(
            docId = docId,
            fragments = locator.locations.fragments,
            href = locator.href.toString(),
            totalProgression = locator.locations.totalProgression,
            updatedAt = updatedAt
        )
}
