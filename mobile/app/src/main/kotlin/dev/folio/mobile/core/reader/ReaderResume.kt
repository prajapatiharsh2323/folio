package dev.folio.mobile.core.reader

/**
 * Within-chapter resume math (audit EPUB-010 — partial).
 *
 * Readium 3.0.0 cannot decode epubcfi text anchors: there are no CFI parser
 * classes in the toolkit (verified via the AAR class list) and the injected
 * reflowable scripts contain no epubcfi support either (verified by scanning
 * the navigator's assets). The audit's "feed the saved CFI back to
 * navigator.go" fix is therefore unreachable in this pin.
 *
 * The sanctioned alternative is implemented here: the saved CFI still picks
 * the CHAPTER, and the within-chapter offset is recomputed from the schema-1
 * `percent` (total publication progression) minus the chapter's total
 * position span (`PositionsService` — the same service
 * `LocatorService.locateProgression` uses). The resulting fraction feeds
 * `Locator.locations.progression`, which the reflowable page's loadLocator
 * honors (scroll offset / page index — verified in R2EpubPageFragment's
 * bytecode).
 */
object ReaderResume {

    /**
     * Fraction (0..1) inside `[startTotal, nextStartTotal)` for a saved total
     * publication progression, clamped to the span.
     *
     * Returns null when the span is unusable (zero/negative width or non-finite
     * inputs) — callers then fall back to the plain chapter-start locator.
     */
    fun inChapterFraction(
        percentTotal: Double,
        startTotal: Double,
        nextStartTotal: Double
    ): Double? {
        val width = nextStartTotal - startTotal
        if (!width.isFinite() || width <= 1e-9) return null
        val fraction = (percentTotal - startTotal) / width
        if (!fraction.isFinite()) return null
        return fraction.coerceIn(0.0, 1.0)
    }
}
