package dev.folio.mobile.ui.components

import androidx.annotation.DrawableRes
import dev.folio.mobile.R
import dev.folio.mobile.core.library.CoverFallback

/**
 * Bundled fallback cover art (the five `cover-imgs` illustrations, normalised
 * to the 5:7 row-cover ratio at 600×840 WebP).
 *
 * Used ONLY when a document has no extractable cover: nothing is written to
 * `meta.files.cover`, so the storage contract, PWA and Drive mirror are
 * untouched. Real EPUB covers always win. The art is chosen from the title
 * seed (`LibraryUi.titleSeed`) via [CoverFallback], so a book keeps its
 * artwork.
 */
object DefaultCoverArt {

    @DrawableRes
    private val arts = intArrayOf(
        R.drawable.folio_cover_art_1,
        R.drawable.folio_cover_art_2,
        R.drawable.folio_cover_art_3,
        R.drawable.folio_cover_art_4,
        R.drawable.folio_cover_art_5,
    )

    val size: Int get() = arts.size

    /** Stable slot for a title seed. */
    fun indexFor(seed: Int): Int = CoverFallback.indexFor(seed, arts.size)

    /** Drawable for [index]; wraps so fan slots can ask for 0..n explicitly. */
    @DrawableRes
    fun resourceAt(index: Int): Int = arts[Math.floorMod(index, arts.size)]
}
