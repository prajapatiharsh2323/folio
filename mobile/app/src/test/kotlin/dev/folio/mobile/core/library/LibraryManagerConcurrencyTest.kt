package dev.folio.mobile.core.library

import dev.folio.mobile.core.importer.ImportOriginal
import dev.folio.mobile.core.importer.ImportReading
import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.store.AppFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Regression suite for the lost-update window on the schema-1 user files
 * (audit EPUB-005): saveProgress/saveAnnotation/saveCollections used to run
 * their decode→modify→write cycle without any mutex, racing deleteDocument
 * (which held the manifest mutex) and each other across LibraryManager
 * instances. Every writer now holds the mutex of the file it mutates
 * (root-keyed so separate instances of the same tree serialize too).
 */
class LibraryManagerConcurrencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store(): AppFileStore = AppFileStore(tmp.newFolder("root-${UUID.randomUUID()}"))

    private fun progress(docId: String, percent: Double) = Progress(
        docId = docId,
        location = "epubcfi(/6/2!/4/2)",
        percent = percent,
        updatedAt = "2026-10-19T00:00:00Z"
    )

    private fun annotation(id: String, docId: String) = Annotation(
        id = id,
        docId = docId,
        quote = "q-$id",
        prefix = "",
        suffix = "",
        kind = AnnotationKind.Highlight,
        createdAt = "2026-10-19T00:00:00Z",
        updatedAt = "2026-10-19T00:00:00Z"
    )

    private fun importResult(title: String) = ImportResult(
        meta = DocMeta(
            id = "pending",
            slug = "pending",
            title = title,
            author = null,
            kind = DocKind.Book,
            capturedAt = "2026-10-19T00:00:00Z",
            importedAt = "2026-10-19T00:00:00Z",
            contentHash = "hash-$title",
            sourceFormat = SourceFormat.Txt
        ),
        original = ImportOriginal("book.txt", "original".encodeToByteArray()),
        reading = ImportReading(
            "read.html",
            "<!doctype html><html><body><h1>$title</h1><p>ch</p></body></html>".encodeToByteArray()
        )
    )

    /** 120 writers hammer progress.json through two distinct manager instances on the same root. */
    @Test
    fun concurrentProgressWritersAcrossInstancesDoNotLoseUpdates() = runBlocking {
        val store = store()
        val a = LibraryManager(store)
        val b = LibraryManager(store)
        val perInstance = 60

        (0 until perInstance).map { i ->
            listOf(
                async(Dispatchers.IO) { a.saveProgress(progress("a-$i", i.toDouble())) },
                async(Dispatchers.IO) { b.saveProgress(progress("b-$i", i.toDouble())) }
            )
        }.flatten().awaitAll()

        val all = a.getAllProgress()
        assertEquals(2 * perInstance, all.size)
    }

    /** deleteDocument's rewrites of progress/annotations must not clobber concurrent writers. */
    @Test
    fun deleteDocumentDoesNotClobberConcurrentProgressAndAnnotationWrites() = runBlocking {
        val store = store()
        val manager = LibraryManager(store)
        val victim = manager.addDocument(importResult("Victim"))
        val survivor = manager.addDocument(importResult("Survivor"))

        // Writers and the delete run CONCURRENTLY — the audit's loss window.
        val iterations = 40
        val writers = (0 until iterations).map { i ->
            listOf(
                async(Dispatchers.IO) {
                    manager.saveProgress(progress(survivor.id, i.toDouble()))
                },
                async(Dispatchers.IO) {
                    manager.saveAnnotation(annotation("ann-$i", survivor.id))
                }
            )
        }.flatten()
        val deleter = async(Dispatchers.IO) { manager.deleteDocument(victim.id) }
        (writers + deleter).awaitAll()

        val p = manager.getProgress(survivor.id)
        assertNotNull(p)
        assertEquals(1, manager.getAllProgress().size) // survivor kept, victim removed
        assertEquals(0, manager.getAllProgress().keys.count { it == victim.id })
        // survivor's annotations all survived the interleaved delete rewrite
        assertTrue(manager.getAnnotations(survivor.id).size >= iterations)
        // victim's traces are gone
        assertEquals(0, manager.getAnnotations(victim.id).size)
        assertEquals(0, manager.getCollections().count { victim.id in it.docIds })
    }

    /** Two manager instances of the same root must serialize collection creation. */
    @Test
    fun concurrentCollectionCreationAcrossInstancesDoesNotLoseUpdates() = runBlocking {
        val store = store()
        val a = LibraryManager(store)
        val b = LibraryManager(store)
        val perInstance = 30

        (0 until perInstance).map { i ->
            listOf(
                async(Dispatchers.IO) { a.createCollection("a-$i") },
                async(Dispatchers.IO) { b.createCollection("b-$i") }
            )
        }.flatten().awaitAll()

        assertEquals(2 * perInstance, a.getCollections().size)
    }

    private fun collection(id: String) = dev.folio.mobile.core.model.Collection(
        id = id,
        name = "c-$id",
        docIds = emptyList(),
        createdAt = "2026-10-19T00:00:00Z"
    )
}
