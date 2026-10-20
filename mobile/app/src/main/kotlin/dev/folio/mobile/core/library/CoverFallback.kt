package dev.folio.mobile.core.library

/**
 * Deterministic selection of bundled cover art for documents whose EPUB (or
 * HTML/TXT source) carried no cover.
 *
 * Pure on purpose: the Compose layer owns the drawable ids, this owns the
 * mapping so a book keeps the same art across launches and the choice stays
 * JVM-tested. [LibraryUi.titleSeed] is the seed — it is already stable and
 * shared by the tint placeholder.
 */
object CoverFallback {

    /**
     * Slot in `0 until count` for a title [seed] (any Int, including negative).
     * Masking first keeps the result non-negative without a sign-dependent
     * remainder.
     */
    fun indexFor(seed: Int, count: Int): Int {
        require(count > 0) { "cover art count must be positive" }
        return (seed and 0x7FFF_FFFF) % count
    }
}
