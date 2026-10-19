package dev.folio.mobile.core.library

import dev.folio.mobile.core.importer.ImportResult
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.Manifest
import dev.folio.mobile.core.model.ManifestDoc
import dev.folio.mobile.core.model.ManifestFile
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.util.createDocSlug
import dev.folio.mobile.core.util.sha256Hex
import dev.folio.mobile.core.store.FileStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

const val MANIFEST_PATH = "library/manifest.json"

/**
 * One orphan doc folder from startup recovery (spec §4 Rule 2). [finishable]
 * when its meta.json parses — the import died after the meta write and can be
 * re-registered; otherwise only deletion makes sense.
 */
data class InterruptedImport(
    val slug: String,
    val title: String?,
    val finishable: Boolean
)

/**
 * Port of packages/core/src/library/library-manager.ts.
 *
 * Spec §4 rules implemented here:
 *  - Rule 1 (write order): original/clean/reading files → meta.json → manifest LAST
 *    (the manifest is the commit point).
 *  - Rule 2 (recovery): docs/ folders not in the manifest are interrupted imports
 *    (recoverInterruptedImports()); damaged entries are surfaced, never dropped.
 *  - Rule 3 (duplicates): findDuplicate() by contentHash — the caller prompts
 *    skip/replace/keep-both.
 *  - Rule 5 (deletion): folder + annotations + progress + collections + index text,
 *    then manifest rewrite.
 */
class LibraryManager(
    private val store: FileStore,
    private val uuidProvider: () -> String = { UUID.randomUUID().toString() },
    private val nowIso: () -> String = { java.time.Instant.now().toString().take(24) + "Z" }
) {

    private val json = Json {
        prettyPrint = true // JSON.stringify(x, null, 2) parity with the PWA
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun mutexFor(key: String): Mutex =
        mutexMap.computeIfAbsent(store.rootKey + ":" + key) { Mutex() }

    companion object {
        // Single source of truth for key strings: FileLock.key.
        private val K_MANIFEST = FileLock.MANIFEST.key
        private val K_PROGRESS = FileLock.PROGRESS.key
        private val K_ANNOTATIONS = FileLock.ANNOTATIONS.key
        private val K_COLLECTIONS = FileLock.COLLECTIONS.key

        /**
         * PROCESS-WIDE mutex registry keyed by "<root>:<file>". Two
         * LibraryManager instances of the same tree (MainActivity + the
         * reader's flush, tests) must serialize against each other, so the
         * map itself lives on the companion — never per instance.
         */
        private val mutexMap = ConcurrentHashMap<String, Mutex>()
    }

    /**
     * Runs [block] holding the mutex of every [keys] entry, acquired one by
     * one in sorted-key order. Sorted acquisition removes the classic
     * AB/BA deadlock when two callers need overlapping key sets.
     */
    private suspend fun <T> withLocks(vararg keys: String, block: suspend () -> T): T {
        val ordered = keys.distinct().sorted()
        suspend fun step(i: Int): T =
            if (i == ordered.size) block()
            else mutexFor(ordered[i]).withLock { step(i + 1) }
        return step(0)
    }

    /**
     * Files guarded by the mutex set above, in a public-usable form for
     * callers (e.g. BackupRestore) that rewrite the schema-1 user state
     * outside this manager.
     */
    enum class FileLock(val key: String) {
        MANIFEST("manifest"),
        PROGRESS("user-progress"),
        ANNOTATIONS("user-annotations"),
        COLLECTIONS("user-collections")
    }

    /** Public wrapper around the multi-key mutex protocol — see [withLocks]. */
    suspend fun <T> lockFiles(vararg locks: FileLock, block: suspend () -> T): T =
        withLocks(*locks.map { it.key }.toTypedArray(), block = block)

    // --- Manifest ---

    /** Reads the current manifest or initializes a new one (corruption-safe). */
    suspend fun getManifest(): Manifest = mutexFor(K_MANIFEST).withLock { readOrCreateManifest() }

    /**
     * Manifest read/init WITHOUT locking — only for writers that already hold
     * the K_MANIFEST mutex (Kotlin Mutex is not re-entrant).
     */
    private suspend fun readOrCreateManifest(): Manifest {
        if (store.exists(MANIFEST_PATH)) {
            try {
                return json.decodeFromString<Manifest>(store.readText(MANIFEST_PATH))
            } catch (e: Exception) {
                // Corrupted/unreadable manifest: do NOT overwrite it (that would
                // silently drop every document). Preserve the file for recovery and
                // surface an empty in-memory view; the caller's recovery scan will
                // re-register orphan folders.
                android.util.Log.e("LibraryManager", "Manifest unreadable; preserving file", e)
                return Manifest(
                    libraryId = uuidProvider(),
                    updatedAt = nowIso(),
                    docs = emptyList()
                )
            }
        }
        val initial = Manifest(
            libraryId = uuidProvider(),
            updatedAt = nowIso(),
            docs = emptyList()
        )
        store.write(MANIFEST_PATH, json.encodeToString(initial))
        return initial
    }

    suspend fun findDuplicate(contentHash: String): ManifestDoc? =
        getManifest().docs.firstOrNull { it.contentHash == contentHash }

    /**
     * Commits an imported document with the exact Section 4 write order.
     */
    suspend fun addDocument(importResult: ImportResult): DocMeta = withLocks(K_MANIFEST) {
        val manifest = readOrCreateManifest()
        val id = uuidProvider()
        val now = nowIso()
        val slug = createDocSlug(importResult.meta.title)

        val docDir = "library/docs/$slug"
        val manifestFiles = mutableListOf<ManifestFile>()

        // 1. Original file
        val origPath = "$docDir/original/${importResult.original.name}"
        store.write(origPath, importResult.original.bytes)
        manifestFiles += ManifestFile(origPath, importResult.original.bytes.size.toLong(), sha256Hex(importResult.original.bytes))

        // 2. Clean files (articles)
        var cleanRel: String? = null
        importResult.clean?.let { clean ->
            val cleanPath = "$docDir/clean/index.html"
            store.write(cleanPath, clean.indexHtml)
            manifestFiles += ManifestFile(cleanPath, clean.indexHtml.toByteArray(Charsets.UTF_8).size.toLong(), sha256Hex(clean.indexHtml.toByteArray(Charsets.UTF_8)))
            cleanRel = "clean/index.html"
            for (img in clean.images) {
                val imgPath = "$docDir/clean/images/${img.name}"
                store.write(imgPath, img.bytes)
                manifestFiles += ManifestFile(imgPath, img.bytes.size.toLong(), sha256Hex(img.bytes))
            }
        }

        // 3. Reading edition
        var readingRel: String? = null
        importResult.reading?.let { reading ->
            val readingPath = "$docDir/reading/${reading.name}"
            store.write(readingPath, reading.bytes)
            manifestFiles += ManifestFile(readingPath, reading.bytes.size.toLong(), sha256Hex(reading.bytes))
            readingRel = "reading/${reading.name}"
        }

        // 4. Cover (optional) — content, before meta.json; spec layout: cover.*
        var coverRel: String? = null
        importResult.cover?.let { cover ->
            val coverPath = "$docDir/${cover.name}"
            store.write(coverPath, cover.bytes)
            manifestFiles += ManifestFile(coverPath, cover.bytes.size.toLong(), sha256Hex(cover.bytes))
            coverRel = cover.name
        }

        // 5. meta.json (full DocMeta)
        val meta = importResult.meta.copy(
            id = id,
            slug = slug,
            importedAt = now,
            files = importResult.meta.files.copy(
                original = "original/${importResult.original.name}",
                clean = cleanRel,
                reading = readingRel,
                cover = coverRel
            )
        )
        val metaText = json.encodeToString(meta)
        val metaPath = "$docDir/meta.json"
        store.write(metaPath, metaText)
        manifestFiles += ManifestFile(metaPath, metaText.toByteArray(Charsets.UTF_8).size.toLong(), sha256Hex(metaText.toByteArray(Charsets.UTF_8)))

        // 6. Search text (index/text/<id>.json) — the per-chapter corpus the
        //    in-memory search index (M4) rebuilds from; PWA-identical path
        if (importResult.searchText.isNotEmpty()) {
            store.write("library/index/text/$id.json", json.encodeToString(importResult.searchText))
        }

        // 7. Manifest LAST — the commit point
        val manifestDoc = ManifestDoc(
            id = id,
            slug = slug,
            title = meta.title,
            contentHash = meta.contentHash,
            updatedAt = now,
            files = manifestFiles
        )
        store.write(MANIFEST_PATH, json.encodeToString(manifest.copy(docs = manifest.docs + manifestDoc, updatedAt = now)))

        meta
    }

    /** Lists all documents with intact meta.json (damaged entries are skipped here; recovery surfaces them). */
    suspend fun listDocuments(): List<DocMeta> {
        val manifest = getManifest()
        val docs = mutableListOf<DocMeta>()
        for (d in manifest.docs) {
            val metaPath = "library/docs/${d.slug}/meta.json"
            if (store.exists(metaPath)) {
                try {
                    docs += json.decodeFromString<DocMeta>(store.readText(metaPath))
                } catch (_: Exception) {
                    // damaged: surfaced by recovery, never silently dropped from manifest
                }
            }
        }
        return docs
    }

    /**
     * Per-chapter searchable text (library/index/text/<id>.json), or empty.
     * Used by the in-memory search index (M4) and nothing else.
     */
    suspend fun getSearchText(docId: String): List<SearchChapterText> {
        val path = "library/index/text/$docId.json"
        if (!store.exists(path)) return emptyList()
        return try {
            json.decodeFromString<List<SearchChapterText>>(store.readText(path))
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Manifest entries whose meta.json is missing or unparsable — shown as damaged, never dropped. */
    suspend fun damagedDocuments(): List<ManifestDoc> {
        val manifest = getManifest()
        return manifest.docs.filter { d ->
            val metaPath = "library/docs/${d.slug}/meta.json"
            !store.exists(metaPath) || runCatching {
                json.decodeFromString<DocMeta>(store.readText(metaPath))
            }.isFailure
        }
    }

    /**
     * Updates editable metadata (title/author/tags/collectionIds) and keeps the
     * manifest entry and search text consistent (spec §5).
     */
    suspend fun updateDocMeta(
        id: String,
        title: String? = null,
        author: String? = null,
        tags: List<String>? = null,
        collectionIds: List<String>? = null
    ): DocMeta = withLocks(K_MANIFEST) {
        updateDocMetaLocked(id, title, author, tags, collectionIds)
    }

    /**
     * Manifest/meta RMW WITHOUT locking — for callers that already hold
     * K_MANIFEST (e.g. [setDocCollections] under its multi-key lock; the
     * Kotlin Mutex is not re-entrant).
     */
    private suspend fun updateDocMetaLocked(
        id: String,
        title: String? = null,
        author: String? = null,
        tags: List<String>? = null,
        collectionIds: List<String>? = null
    ): DocMeta {
        val manifest = readOrCreateManifest()
        val mDoc = manifest.docs.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Document not found in manifest: $id")
        val metaPath = "library/docs/${mDoc.slug}/meta.json"
        val current = json.decodeFromString<DocMeta>(store.readText(metaPath))

        val updated = current.copy(
            title = title ?: current.title,
            author = author ?: current.author,
            tags = tags ?: current.tags,
            collectionIds = collectionIds ?: current.collectionIds
        )
        val updatedText = json.encodeToString(updated)
        store.write(metaPath, updatedText)

        val now = nowIso()
        val newFiles = manifest.docs.map {
            if (it.id != id) it else it.copy(
                title = updated.title,
                updatedAt = now,
                files = it.files.map { f ->
                    if (f.path == metaPath) f.copy(
                        size = updatedText.toByteArray(Charsets.UTF_8).size.toLong(),
                        sha256 = sha256Hex(updatedText.toByteArray(Charsets.UTF_8))
                    ) else f
                }
            )
        }
        store.write(MANIFEST_PATH, json.encodeToString(manifest.copy(docs = newFiles, updatedAt = now)))
        return updated
    }

    /**
     * Deletes a document and every trace of it, then rewrites the manifest last
     * (spec §4 Rule 5). Holds the union of the manifest + user-file locks so a
     * concurrent saveProgress/saveAnnotation/saveCollections cannot resurrect
     * the deleted facts into a fresh JSON write (audit EPUB-005).
     */
    suspend fun deleteDocument(id: String) = withLocks(K_MANIFEST, K_PROGRESS, K_ANNOTATIONS, K_COLLECTIONS) {
        val manifest = readOrCreateManifest()
        val mDoc = manifest.docs.firstOrNull { it.id == id } ?: return@withLocks
        val docDir = "library/docs/${mDoc.slug}"

        // 1. Remove folder
        try {
            store.remove(docDir, recursive = true)
        } catch (_: Exception) {
            // removal failure is non-fatal; manifest will still drop the entry
        }

        // 2. Search text
        try {
            if (store.exists("library/index/text/$id.json")) {
                store.remove("library/index/text/$id.json")
            }
        } catch (_: Exception) {
        }

        // 3. Progress
        try {
            val progressPath = "library/user/progress.json"
            if (store.exists(progressPath)) {
                val map = json.decodeFromString<Map<String, Progress>>(store.readText(progressPath))
                if (map.containsKey(id)) {
                    store.write(progressPath, json.encodeToString(map - id))
                }
            }
        } catch (_: Exception) {
        }

        // 4. Annotations
        try {
            val annotPath = "library/user/annotations.json"
            if (store.exists(annotPath)) {
                val all = json.decodeFromString<List<Annotation>>(store.readText(annotPath))
                store.write(annotPath, json.encodeToString(all.filter { it.docId != id }))
            }
        } catch (_: Exception) {
        }

        // 5. Collections membership
        try {
            val collPath = "library/user/collections.json"
            if (store.exists(collPath)) {
                val all = json.decodeFromString<List<Collection>>(store.readText(collPath))
                var changed = false
                val updated = all.map { c ->
                    if (id in c.docIds) {
                        changed = true
                        c.copy(docIds = c.docIds - id)
                    } else c
                }
                if (changed) store.write(collPath, json.encodeToString(updated))
            }
        } catch (_: Exception) {
        }

        // 6. Manifest last
        val now = nowIso()
        store.write(MANIFEST_PATH, json.encodeToString(manifest.copy(docs = manifest.docs - mDoc, updatedAt = now)))
    }
    /**
     * Startup recovery per spec §4 Rule 2: docs/ folders not listed in the
     * manifest are interrupted imports (caller offers finish or delete).
     */
    suspend fun recoverInterruptedImports(): List<String> {
        val manifest = getManifest()
        val registered = manifest.docs.map { it.slug }.toSet()
        return store.list("library/docs").filter { it !in registered }
    }

    /** Reads an orphan folder's meta.json; null when missing or unparsable. */
    private suspend fun readOrphanMeta(slug: String): DocMeta? {
        val metaPath = "library/docs/$slug/meta.json"
        if (!store.exists(metaPath)) return null
        return runCatching { json.decodeFromString<DocMeta>(store.readText(metaPath)) }.getOrNull()
    }

    /** Recursive file list under [dir] (FileStore.list is non-recursive). */
    private suspend fun walkFiles(dir: String): List<String> {
        val out = mutableListOf<String>()
        for (name in store.list(dir)) {
            val child = "$dir/$name"
            if (store.exists(child)) out += child else out += walkFiles(child)
        }
        return out
    }

    /**
     * Inspects orphan folders: [InterruptedImport.finishable] when meta.json
     * parses (the import reached the meta write, so it can be re-registered).
     */
    suspend fun scanInterruptedImports(): List<InterruptedImport> =
        recoverInterruptedImports().map { slug ->
            val meta = readOrphanMeta(slug)
            InterruptedImport(slug = slug, title = meta?.title, finishable = meta != null)
        }

    /**
     * Finishes an interrupted import (spec §4 Rule 2 "finish"): re-registers
     * the orphan folder's meta.json into the manifest, rebuilding the entry's
     * file list (path/size/sha256) from disk. Manifest is written last.
     */
    suspend fun finishInterruptedImport(slug: String): DocMeta =
        mutexFor("finish-$slug").withLock {
            val meta = readOrphanMeta(slug)
                ?: throw IllegalArgumentException("No readable meta.json for interrupted import: $slug")
            val manifest = getManifest()
            if (manifest.docs.any { it.slug == slug || it.id == meta.id }) {
                throw IllegalArgumentException("Already registered: $slug")
            }
            val files = walkFiles("library/docs/$slug").map { path ->
                val bytes = store.read(path)
                ManifestFile(path, bytes.size.toLong(), sha256Hex(bytes))
            }
            val now = nowIso()
            val manifestDoc = ManifestDoc(
                id = meta.id,
                slug = slug,
                title = meta.title,
                contentHash = meta.contentHash,
                updatedAt = now,
                files = files
            )
            store.write(
                MANIFEST_PATH,
                json.encodeToString(manifest.copy(docs = manifest.docs + manifestDoc, updatedAt = now))
            )
            meta
        }

    /**
     * Deletes an interrupted import's leftover folder (spec §4 Rule 2
     * "delete") plus its index text when the id is known. Refuses registered
     * slugs — those go through [deleteDocument].
     */
    suspend fun discardInterruptedImport(slug: String) = mutexFor("discard-$slug").withLock {
        if (getManifest().docs.any { it.slug == slug }) {
            throw IllegalArgumentException("Registered document — use deleteDocument: $slug")
        }
        readOrphanMeta(slug)?.id?.let { id ->
            try {
                if (store.exists("library/index/text/$id.json")) {
                    store.remove("library/index/text/$id.json")
                }
            } catch (_: Exception) {
            }
        }
        try {
            store.remove("library/docs/$slug", recursive = true)
        } catch (_: Exception) {
            // non-fatal: the folder will resurface in the next recovery scan
        }
    }

    // --- Progress ---

    suspend fun saveProgress(progress: Progress) = mutexFor(K_PROGRESS).withLock {
        val progressPath = "library/user/progress.json"
        val map: Map<String, Progress> = runCatching {
            if (store.exists(progressPath)) {
                json.decodeFromString<Map<String, Progress>>(store.readText(progressPath))
            } else emptyMap()
        }.getOrElse { emptyMap() }
        store.write(progressPath, json.encodeToString(map + (progress.docId to progress)))
    }

    suspend fun getProgress(docId: String): Progress? {
        val progressPath = "library/user/progress.json"
        if (!store.exists(progressPath)) return null
        return runCatching {
            json.decodeFromString<Map<String, Progress>>(store.readText(progressPath))[docId]
        }.getOrNull()
    }

    suspend fun getAllProgress(): Map<String, Progress> {
        val progressPath = "library/user/progress.json"
        if (!store.exists(progressPath)) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, Progress>>(store.readText(progressPath))
        }.getOrElse { emptyMap() }
    }

    // --- Annotations ---

    suspend fun getAnnotations(docId: String? = null): List<Annotation> {
        val annotPath = "library/user/annotations.json"
        if (!store.exists(annotPath)) return emptyList()
        return runCatching {
            val all = json.decodeFromString<List<Annotation>>(store.readText(annotPath))
            docId?.let { filter -> all.filter { it.docId == filter } } ?: all
        }.getOrElse { emptyList() }
    }

    suspend fun saveAnnotation(annotation: Annotation) = mutexFor(K_ANNOTATIONS).withLock {
        val annotPath = "library/user/annotations.json"
        val all = runCatching {
            if (store.exists(annotPath)) {
                json.decodeFromString<List<Annotation>>(store.readText(annotPath))
            } else emptyList()
        }.getOrElse { emptyList() }
        val without = all.filter { it.id != annotation.id }
        store.write(annotPath, json.encodeToString(without + annotation))
    }

    suspend fun removeAnnotation(id: String): Unit = mutexFor(K_ANNOTATIONS).withLock {
        val annotPath = "library/user/annotations.json"
        if (!store.exists(annotPath)) return@withLock
        val all = runCatching {
            json.decodeFromString<List<Annotation>>(store.readText(annotPath))
        }.getOrElse { return@withLock }
        store.write(annotPath, json.encodeToString(all.filter { it.id != id }))
    }

    // --- Collections ---

    suspend fun getCollections(): List<Collection> {
        val collPath = "library/user/collections.json"
        if (!store.exists(collPath)) return emptyList()
        return runCatching {
            json.decodeFromString<List<Collection>>(store.readText(collPath))
        }.getOrElse { emptyList() }
    }

    suspend fun saveCollections(collections: List<Collection>): Unit =
        mutexFor(K_COLLECTIONS).withLock {
            store.write("library/user/collections.json", json.encodeToString(collections))
        }

    /** Creates a collection (PWA parity: fresh id, empty docIds, now). */
    suspend fun createCollection(name: String): Collection = mutexFor(K_COLLECTIONS).withLock {
        val coll = Collection(
            id = uuidProvider(),
            name = name,
            docIds = emptyList(),
            createdAt = nowIso()
        )
        store.write(
            "library/user/collections.json",
            json.encodeToString(getCollections() + coll)
        )
        coll
    }

    /** Renames a collection; throws when the id is unknown (PWA parity). */
    suspend fun renameCollection(id: String, name: String): Collection =
        mutexFor(K_COLLECTIONS).withLock {
            val collections = getCollections()
            val coll = collections.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Collection not found: $id")
            val renamed = coll.copy(name = name)
            store.write(
                "library/user/collections.json",
                json.encodeToString(collections.map { if (it.id == id) renamed else it })
            )
            renamed
        }

    /** Deletes a collection; document meta keeps its ids until reassigned (PWA parity). */
    suspend fun deleteCollection(id: String): Unit = mutexFor(K_COLLECTIONS).withLock {
        val collections = getCollections()
        store.write(
            "library/user/collections.json",
            json.encodeToString(collections.filter { it.id != id })
        )
    }

    /**
     * Assigns a document to exactly [collectionIds] (spec §5): syncs membership
     * in every collection file, then the doc's meta/manifest/search text.
     * (Reads of collections inside the held lock are fine; reads are lockless
     * since AppFileStore's atomic rename never exposes torn files.)
     */
    suspend fun setDocCollections(docId: String, collectionIds: List<String>): Unit =
        withLocks(K_COLLECTIONS, K_MANIFEST) {
            val collections = getCollections()
            val updates = collections.map { coll ->
                val has = docId in coll.docIds
                val shouldHave = coll.id in collectionIds
                when {
                    shouldHave && !has -> coll.copy(docIds = coll.docIds + docId)
                    !shouldHave && has -> coll.copy(docIds = coll.docIds - docId)
                    else -> coll
                }
            }
            store.write("library/user/collections.json", json.encodeToString(updates))
            updateDocMetaLocked(docId, collectionIds = collectionIds)
        }
}
