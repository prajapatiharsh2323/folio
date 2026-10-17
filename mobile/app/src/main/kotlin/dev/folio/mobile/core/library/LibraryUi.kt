package dev.folio.mobile.core.library

import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.ManifestDoc
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
        val colorSeed: Int,
        /** store-relative cover path (e.g. "library/docs/<slug>/cover.jpg"), when present. */
        val cover: String? = null,
        /** doc tags (lowercase, PWA contract) for filtering + the details dialog. */
        val tags: List<String> = emptyList(),
        /** collection ids the doc belongs to (kept in sync by setDocCollections). */
        val collectionIds: List<String> = emptyList()
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
            colorSeed = titleSeed(meta.title.ifBlank { meta.slug }),
            cover = meta.files.cover?.takeIf { it.isNotBlank() }
                ?.let { "library/docs/${meta.slug}/$it" },
            tags = meta.tags.filter { it.isNotBlank() },
            collectionIds = meta.collectionIds
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

    /** One damaged-doc row in the recovery banner/dialog (display-only). */
    data class DamagedItem(val id: String, val label: String)

    /** Recovery-banner content; null when nothing is damaged. */
    data class DamagedNotice(val count: Int, val bannerText: String, val items: List<DamagedItem>)

    /**
     * Pure display model for damaged docs (spec §4 Rule 2: shown, never
     * silently dropped). Removal reuses [LibraryManager.deleteDocument].
     */
    fun damagedNotice(damaged: List<ManifestDoc>): DamagedNotice? {
        if (damaged.isEmpty()) return null
        val items = damaged.map { DamagedItem(it.id, it.title.ifBlank { it.slug }) }
        val text = if (items.size == 1) {
            "1 document is damaged — its files are incomplete."
        } else {
            "${items.size} documents are damaged — their files are incomplete."
        }
        return DamagedNotice(items.size, text, items)
    }

    /** One interrupted-import row (spec §4 Rule 2): finishable or delete-only. */
    data class OrphanItem(val slug: String, val label: String, val finishable: Boolean)

    /** Interrupted-import banner content; null when none. */
    data class OrphanNotice(val count: Int, val bannerText: String, val items: List<OrphanItem>)

    /**
     * Pure display model for interrupted imports (spec §4 Rule 2 "offer to
     * finish it or delete it"). Finish/discard reuse the LibraryManager APIs.
     */
    fun orphanNotice(orphans: List<InterruptedImport>): OrphanNotice? {
        if (orphans.isEmpty()) return null
        val items = orphans.map {
            OrphanItem(
                slug = it.slug,
                label = it.title?.takeIf { t -> t.isNotBlank() } ?: it.slug,
                finishable = it.finishable
            )
        }
        val text = if (items.size == 1) {
            "1 interrupted import found — finish or delete it."
        } else {
            "${items.size} interrupted imports found — finish or delete them."
        }
        return OrphanNotice(items.size, text, items)
    }

    /** One chip in the library filter row (exclusive selection). */
    sealed interface LibraryFilter {
        data class TagFilter(val tag: String) : LibraryFilter
        data class CollectionFilter(val id: String, val name: String) : LibraryFilter
    }

    /** Filter chips: collections in file order, then unique tags A→Z (PWA parity). */
    fun filters(collections: List<Collection>, items: List<LibraryItem>): List<LibraryFilter> {
        val tags = items.flatMap { it.tags }.filter { it.isNotBlank() }.distinct().sorted()
        return collections.map { LibraryFilter.CollectionFilter(it.id, it.name) } +
            tags.map { LibraryFilter.TagFilter(it) }
    }

    /** Applies the active filter (null = show everything). */
    fun applyFilter(items: List<LibraryItem>, filter: LibraryFilter?): List<LibraryItem> = when (filter) {
        null -> items
        is LibraryFilter.TagFilter -> items.filter { filter.tag in it.tags }
        is LibraryFilter.CollectionFilter -> items.filter { filter.id in it.collectionIds }
    }

    /** Same filter target (collection id / tag name), ignoring renamed labels. */
    fun sameTarget(a: LibraryFilter, b: LibraryFilter): Boolean = when {
        a is LibraryFilter.TagFilter && b is LibraryFilter.TagFilter -> a.tag == b.tag
        a is LibraryFilter.CollectionFilter && b is LibraryFilter.CollectionFilter -> a.id == b.id
        else -> false
    }

    /**
     * Re-binds a selected filter to refreshed [options], matched by target so a
     * renamed collection keeps its chip selected; null when the target
     * disappeared (deleted collection, removed tag).
     */
    fun rebind(selected: LibraryFilter?, options: List<LibraryFilter>): LibraryFilter? =
        selected?.let { target -> options.firstOrNull { sameTarget(it, target) } }

    /**
     * Tag input rule (PWA DocumentSheet parity): trim + lowercase; null when
     * empty or already present.
     */
    fun normalizeTag(input: String, existing: List<String>): String? {
        val clean = input.trim().lowercase()
        return clean.takeIf { it.isNotEmpty() && it !in existing }
    }
}
