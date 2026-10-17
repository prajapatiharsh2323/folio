package dev.folio.mobile.core.search

import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.DocMeta

/** Pure chapter-entry builder (PWA getSearchIndex mapping, JVM-testable). */
object SearchEntries {

    fun entriesFor(meta: DocMeta, chapters: List<SearchChapterText>): List<SearchIndex.Entry> =
        chapters.map { st ->
            SearchIndex.Entry(
                id = "${meta.id}:${st.chapterId}",
                docId = meta.id,
                chapterId = st.chapterId,
                title = meta.title,
                chapterTitle = st.title,
                author = meta.author ?: "",
                tags = meta.tags,
                text = st.text
            )
        }

    /** chapterId -> spine order (1-based; searchText order == meta.chapters order). */
    fun chapterOrders(meta: DocMeta): Map<String, Int> =
        meta.chapters.associate { it.id to it.order }
}

/**
 * v1 search facade: builds the in-memory index from the library's per-chapter
 * searchText files and runs a query. The index is rebuilt once per
 * [build] call (screen-open scale works: reading files only), then queries run
 * purely in memory on the engine.
 */
class SearchService(private val library: LibraryManager) {

    data class DocResult(
        val group: SearchIndex.Group,
        /** chapterId -> 1-based spine order, for opening the doc at a hit. */
        val chapterOrders: Map<String, Int>
    )

    class Session(
        private val index: SearchIndex,
        private val orderMaps: Map<String, Map<String, Int>>
    ) {
        fun search(query: String): List<DocResult> =
            index.search(query).map { group ->
                DocResult(group, orderMaps[group.docId] ?: emptyMap())
            }
    }

    suspend fun build(): Session {
        val index = SearchIndex()
        val orderMaps = HashMap<String, Map<String, Int>>()
        for (meta in library.listDocuments()) {
            orderMaps[meta.id] = SearchEntries.chapterOrders(meta)
            SearchEntries.entriesFor(meta, library.getSearchText(meta.id)).forEach { index.add(it) }
        }
        return Session(index, orderMaps)
    }
}
