package dev.folio.mobile.core.library

import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.Progress

/**
 * Pure library-screen display model (v1 library phase) — strings/badges only,
 * JVM-testable. No Bookmark icon/tap logic; the Compose layer maps straight
 * from [LibraryItem].
 */
object LibraryUi {

    data class LibraryItem(
        val id: String,
        val title: String,
        /** author, or a kind badge when the author is missing. */
        val subtitle: String,
        val kindLabel: String,
        /** total publication progress 0..1, or null when never opened. */
        val percent: Double?,
        /** "42%" for the trailing progress label. Empty when never opened. */
        val percentLabel: String,
        /** stable color seed for the placeholder cover. */
        val colorSeed: Int
    )

    fun item(meta: DocMeta, progress: Progress?): LibraryItem {
        val kindLabel = when (meta.kind) {
            DocKind.Book -> "book"
            DocKind.Article -> "article"
        }
        return LibraryItem(
            id = meta.id,
            title = meta.title.ifBlank { meta.slug },
            subtitle = meta.author?.takeIf { it.isNotBlank() } ?: kindLabel,
            kindLabel = kindLabel,
            percent = progress?.percent?.coerceIn(0.0, 1.0),
            percentLabel = progress?.let { "${(it.percent.coerceIn(0.0, 1.0) * 100).toInt()}%" } ?: "",
            colorSeed = titleSeed(meta.title.ifBlank { meta.slug })
        )
    }

    /** FNV-1a — cheap, stable across launches, no seeding surprises. */
    fun titleSeed(title: String): Int {
        var hash = 0x811C9DC5
        for (ch in title.lowercase()) {
            hash = (hash xor ch.code.toLong()) * 0x01000193L
        }
        return hash.toInt()
    }

    /** Library ordering: in-progress docs first, then untouched, then finished; A→Z within. */
    fun sort(items: List<LibraryItem>): List<LibraryItem> = items.sortedWith(
        compareBy(
            ::bucket,
            { it: LibraryItem -> it.title.lowercase() }
        )
    )

    /** 0 in-progress, 1 untouched (never opened), 2 finished (percent == 1). */
    private fun bucket(item: LibraryItem): Int = when {
        item.percent == null -> 1
        item.percent >= 1.0 - 1e-9 -> 2
        else -> 0
    }
}
