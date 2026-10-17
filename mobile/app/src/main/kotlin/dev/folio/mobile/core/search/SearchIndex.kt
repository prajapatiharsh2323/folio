package dev.folio.mobile.core.search

/**
 * Port of the PWA's SearchIndex (packages/core/src/search/index.ts) on top of
 * [MiniSearchEngine]. Contract identical: Section 7 boosts title4/author3/
 * tags3/text1, prefix everywhere, AND combination, Latin-only fuzzy 0.2 for
 * terms longer than 4 chars, grouped-by-doc results capped at 50 documents,
 * snippets from raw chapter texts.
 *
 * v1 scope note (documented in MOBILE-TRACKING): the PWA persists search.json
 * (drive-synced); the mobile index is rebuilt in memory on demand — entries
 * come from the library's per-chapter searchText files and DocMeta. This keeps
 * schema-1 untouched and, per the user-approved decision (2026-10-17), avoids
 * SQLDelight/FTS5 entirely (Android platform SQLite has no FTS5, and the PWA
 * reference engine is in-memory anyway).
 */
class SearchIndex {

    data class Entry(
        val id: String, // docId:chapterId
        val docId: String,
        val chapterId: String,
        val title: String,
        val chapterTitle: String,
        val author: String,
        val tags: List<String>,
        val text: String
    )

    data class ChapterHit(
        val chapterId: String,
        val chapterTitle: String,
        val score: Double,
        val snippet: String
    )

    data class Group(
        val docId: String,
        val title: String,
        val author: String,
        val hits: List<ChapterHit>
    )

    private val engine = MiniSearchEngine(
        fields = listOf("title", "author", "tags", "text"),
        boost = mapOf("title" to 4.0, "author" to 3.0, "tags" to 3.0, "text" to 1.0),
        processTerm = { SearchNormalise.normalise(it) },
        tokenize = { SearchNormalise.tokenise(it) },
        fuzzyRatio = { term ->
            if (Regex("[a-z]+").matches(term) && term.length > 4) 0.2 else null
        },
        prefixEnabled = true,
        combineWith = MiniSearchEngine.COMBINE_AND
    )

    private val docChaptersMap = HashMap<String, MutableList<String>>()
    private val rawTextsMap = HashMap<String, String>()

    /** add-or-replace (PWA's add() with the discard fallback baked in). */
    fun add(entry: Entry) {
        docChaptersMap.getOrPut(entry.docId) { ArrayList() }.let { chapters ->
            if (entry.chapterId !in chapters) chapters.add(entry.chapterId)
        }
        if (entry.text.isNotEmpty()) rawTextsMap[entry.id] = entry.text
        if (engine.has(entry.id)) engine.discard(entry.id)
        engine.add(entry.asFields())
    }

    /** Dedupes by id within the batch, last wins (PWA addAll contract). */
    fun addAll(entries: List<Entry>) {
        entries.reversed().distinctBy { it.id }.reversed().forEach { add(it) }
    }

    fun removeByDocId(docId: String) {
        val chapters = docChaptersMap.remove(docId) ?: return
        for (ch in chapters) {
            val id = "$docId:$ch"
            rawTextsMap.remove(id)
            engine.discard(id)
        }
    }

    fun search(
        query: String,
        rawTexts: Map<String, String>? = null
    ): List<Group> {
        if (query.isBlank()) return emptyList()
        val rawHits = engine.search(query)
        val queryTokens = SearchNormalise.tokenise(query)

        val docMap = LinkedHashMap<String, Group>()
        for (hit in rawHits) {
            val stored = engine.getStoredFields(hit.docId) ?: continue
            @Suppress("UNCHECKED_CAST")
            val fields = stored as Map<String, Any?>
            val docId = fields["docId"] as? String ?: continue
            val chapterId = fields["chapterId"] as? String ?: ""
            val title = fields["title"] as? String ?: ""
            val chapterTitle = fields["chapterTitle"] as? String ?: ""
            val author = fields["author"] as? String ?: ""

            val rawText = rawTexts?.get("$docId:$chapterId")
                ?: rawTextsMap["$docId:$chapterId"]
                ?: ""
            val snippet = SearchSnippet.build(rawText, queryTokens)

            val group = docMap.getOrPut(docId) {
                Group(docId, title, author, emptyList())
            }
            val updated = group.copy(hits = group.hits + ChapterHit(chapterId, chapterTitle, hit.score, snippet))
            docMap[docId] = updated

            // Section 7: at most 50 documents
            if (docMap.size >= 50) break
        }
        return docMap.values.filter { it.hits.isNotEmpty() }.toList()
    }

    /** Same shape + sizes the engine would produce for a fresh add (tests). */
    fun entryCountFor(entry: Entry): String = entry.id

    private fun Entry.asFields(): Map<String, Any?> = mapOf(
        "id" to id,
        "docId" to docId,
        "chapterId" to chapterId,
        "title" to title,
        "chapterTitle" to chapterTitle,
        "author" to author,
        "tags" to tags,
        "text" to text
    )
}
