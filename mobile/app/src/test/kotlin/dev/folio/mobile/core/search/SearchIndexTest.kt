package dev.folio.mobile.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports the behavioral cases of packages/core/tests/search.test.ts (Section 7)
 * against the pure-Kotlin MiniSearchPort.
 */
class SearchIndexTest {

    private fun entry(
        id: String,
        docId: String,
        chapterId: String,
        title: String,
        chapterTitle: String,
        author: String,
        tags: List<String>,
        text: String
    ) = SearchIndex.Entry(id, docId, chapterId, title, chapterTitle, author, tags, text)

    @Test
    fun `hindi word finds documents containing it`() {
        val index = SearchIndex()
        index.add(
            entry(
                "doc1:ch1", "doc1", "ch1",
                "गंगा यात्रा", "शुरुआत", "राहुल",
                listOf("यात्रा"),
                "गंगा की लहरें अत्यंत पावन और निर्मल हैं।"
            )
        )
        index.add(
            entry(
                "doc2:ch1", "doc2", "ch1",
                "हिमालय दर्शन", "पर्वत", "सुमित्रानंदन",
                listOf("प्रकृति"),
                "पहाड़ों के ऊपर बर्फ और शांति का साम्राज्य है।"
            )
        )

        val results = index.search("गंगा")
        assertEquals(1, results.size)
        assertEquals("गंगा यात्रा", results[0].title)
    }

    @Test
    fun `prefix matching finds inflected hindi forms`() {
        val index = SearchIndex()
        index.add(
            entry(
                "doc1:ch1", "doc1", "ch1",
                "कहानी", "भाग १", "प्रेमचंद", emptyList(),
                "विद्यार्थियों ने अपनी पुस्तकें खोलीं और ध्यान से अध्ययन किया।"
            )
        )
        val results = index.search("विद्यार्थी")
        assertEquals(1, results.size)
        assertEquals("doc1", results[0].docId)
    }

    @Test
    fun `zwj-typed query still matches`() {
        val index = SearchIndex()
        val withZwj = "नमस्\u200Dते"
        index.add(
            entry(
                "doc1:ch1", "doc1", "ch1",
                "संवाद", "मिलन", "लेखक", emptyList(),
                "सभी को $withZwj कहा गया।"
            )
        )
        assertEquals(1, index.search("नमस्ते").size)
    }

    @Test
    fun `nukta decomposed query matches precomposed text`() {
        val index = SearchIndex()
        val precomposed = "फ़िल्म"
        val decomposed = java.text.Normalizer.normalize(precomposed, java.text.Normalizer.Form.NFD)
        index.add(
            entry(
                "doc1:ch1", "doc1", "ch1",
                "सिनेमा", "समीक्षा", "समीक्षक", emptyList(),
                "यह $precomposed बहुत ज्ञानवर्धक है।"
            )
        )
        assertEquals(1, index.search(decomposed).size)
    }

    @Test
    fun `mixed hindi and english query`() {
        val index = SearchIndex()
        index.add(
            entry(
                "doc1:ch1", "doc1", "ch1",
                "Modern Hindi Literature", "Ch 1", "Sharma",
                listOf("history"),
                "The translation of गीता into modern prose established a new literary standard."
            )
        )
        assertEquals(1, index.search("translation गीता").size)
    }

    @Test
    fun `latin accented query matches unaccented text`() {
        val index = SearchIndex()
        index.add(
            entry(
                "doc1:ch1", "doc1", "ch1",
                "Cafe Culture", "Paris", "Author", emptyList(),
                "In the famous cafe, readers spent silent mornings."
            )
        )
        assertEquals(1, index.search("café").size)
    }

    @Test
    fun `documents list is capped at 50 groups`() {
        val index = SearchIndex()
        for (i in 1..60) {
            index.add(entry("d$i:ch1", "d$i", "ch1", "T$i", "Ch", "A", emptyList(), "needle word $i"))
        }
        val groups = index.search("needle")
        assertEquals(50, groups.size)
    }

    @Test
    fun `fuzzy only kicks in for latin terms longer than 4 chars`() {
        val index = SearchIndex()
        index.add(entry("d1:ch1", "d1", "ch1", "Neuromancer", "Ch", "A", emptyList(), "the neuromancer password"))
        // 5+ chars: neuromancer-ish typo with distance ≤ round(len*0.2)
        assertEquals(1, index.search("neromancer").size)
        // short latin term: no fuzzy
        assertEquals(0, index.search("cafe".replace("c", "x")).size)
    }

    @Test
    fun `removeByDocId clears the group`() {
        val index = SearchIndex()
        index.add(entry("d1:ch1", "d1", "ch1", "T", "Ch", "A", emptyList(), "Unique Needle Here"))
        index.add(entry("d2:ch1", "d2", "ch1", "T", "Ch", "A", emptyList(), "Unique Needle There"))
        assertEquals(2, index.search("needle").size)
        index.removeByDocId("d1")
        val groups = index.search("needle")
        assertEquals(1, groups.size)
        assertEquals("d2", groups[0].docId)
    }

    @Test
    fun `duplicate ids re-add without throwing, last wins`() {
        val index = SearchIndex()
        index.add(entry("doc1:ch1", "doc1", "ch1", "Doc One", "Chapter 1", "Author", emptyList(), "First version of chapter 1"))
        index.add(entry("doc1:ch1", "doc1", "ch1", "Doc One", "Chapter 1", "Author", emptyList(), "Updated version of chapter 1"))

        index.addAll(
            listOf(
                entry("doc2:ch-2", "doc2", "ch-2", "Doc Two", "Chapter 2", "Author", emptyList(), "Initial text for ch-2"),
                entry("doc2:ch-2", "doc2", "ch-2", "Doc Two", "Chapter 2", "Author", emptyList(), "Duplicate text for ch-2 in same batch"),
                entry("doc1:ch1", "doc1", "ch1", "Doc One", "Chapter 1", "Author", emptyList(), "Batch re-indexing of existing doc1")
            )
        )
        val results = index.search("Duplicate")
        assertEquals(1, results.size)
        assertEquals("doc2", results[0].docId)
    }

    @Test
    fun `raw texts can be supplied externally for snippets`() {
        val entry = SearchIndex.Entry(
            "book-1:ch-1", "book-1", "ch-1",
            "The Cedar Solitary Reader", "Chapter 1", "Arthur Pendelton",
            listOf("novel"), ""
        )
        // Postings come from title/tags only (the "reloaded index" shape the PWA's
        // loadJSON produces — raw chapter texts are not persisted in search.json);
        // the snippet must then come from the externally supplied rawTexts map.
        val fresh = SearchIndex()
        fresh.add(entry)
        val results = fresh.search(
            "cedar",
            mapOf("book-1:ch-1" to "The sun slanted across the cedar bookshelves of the library in warm autumn light.")
        )
        assertEquals(1, results.size)
        assertEquals("book-1", results[0].docId)
        assertTrue(results[0].hits[0].snippet.contains("<mark>cedar</mark>"))
    }

    @Test
    fun `section 7 benchmark - 500 docs under 100 ms`() {
        val index = SearchIndex()
        val entries = (1..500).map { i ->
            entry(
                "doc-$i:ch-1", "doc-$i", "ch-1",
                "Book Title $i - ${if (i % 2 == 0) "दर्शन" else "Philosophy"}", "Chapter 1", "Author $i",
                listOf("tag-${i % 10}"),
                "In this chapter $i, we explore knowledge, silence, and nature. " +
                    (if (i % 5 == 0) "यह ज्ञान का अनंत विस्तार है।" else "An inquiry into natural laws.")
            )
        }
        index.addAll(entries)

        // Untimed warmup (JIT) — the Section-7 budget is about engine scale, not
        // first-call interpreter cost.
        index.search("ज्ञान")

        val start = System.nanoTime()
        val hits = index.search("ज्ञान विस्तार")
        val ms = (System.nanoTime() - start) / 1_000_000

        assertTrue("expected hits", hits.isNotEmpty())
        assertTrue("took ${ms}ms", ms < 100)
    }
}
