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
const val PROGRESS_PATH = "library/user/progress.json"
const val ANNOTATIONS_PATH = "library/user/annotations.json"
const val COLLECTIONS_PATH = "library/user/collections.json"
/** Recovery copies of unreadable files land here (never over the originals). */
const val RECOVERY_DIR = "library/recovery"

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

    /**
     * Reads the current manifest or initializes a new one.
     *
     * Data-safety contract (audit P0-01): when the manifest exists but cannot
     * be decoded this THROWS [LibraryFileCorruptException] — a corrupt index is
     * never handed to callers as a valid empty library, because mutating
     * callers (import, delete, finish recovery, backup merge) would then
     * persist that empty view over the only copy of the index.
     */
    suspend fun getManifest(): Manifest = mutexFor(K_MANIFEST).withLock { readOrCreateManifest() }

    /** Typed manifest read for UIs: Missing (fresh library) vs Corrupt vs Loaded. */
    suspend fun readManifestState(): ReadResult<Manifest> =
        mutexFor(K_MANIFEST).withLock { readManifestStateLocked() }

    private suspend fun readManifestStateLocked(): ReadResult<Manifest> {
        if (!store.exists(MANIFEST_PATH)) return ReadResult.Missing
        return try {
            ReadResult.Loaded(json.decodeFromString<Manifest>(store.readText(MANIFEST_PATH)))
        } catch (e: Exception) {
            ReadResult.Corrupt(MANIFEST_PATH, e.message ?: e.toString())
        }
    }

    /**
     * Manifest read/init WITHOUT locking — only for writers that already hold
     * the K_MANIFEST mutex (Kotlin Mutex is not re-entrant).
     */
    private suspend fun readOrCreateManifest(): Manifest {
        when (val state = readManifestStateLocked()) {
            is ReadResult.Loaded -> return state.value
            is ReadResult.Corrupt -> {
                // Corrupted manifest: do NOT overwrite it (that would silently
                // drop every document) and do NOT hand writers an empty view.
                // Preserve the file byte-for-byte for recovery; mutations stay
                // blocked until repairManifest() or a backup restore succeeds.
                android.util.Log.e(
                    "LibraryManager",
                    "Manifest unreadable; mutations blocked — ${state.message}"
                )
                throw LibraryFileCorruptException(state.path, state.message)
            }
            is ReadResult.Missing -> {
                val initial = Manifest(
                    libraryId = uuidProvider(),
                    updatedAt = nowIso(),
                    docs = emptyList()
                )
                store.write(MANIFEST_PATH, json.encodeToString(initial))
                return initial
            }
        }
    }

    /** Summary of [repairManifest]. */
    data class ManifestRepairResult(
        /** Preserved copy of the original corrupt manifest (library-relative path). */
        val preservedBackupPath: String,
        /** Number of documents rebuilt from library/docs folders. */
        val registered: Int,
        /** Orphan folders whose meta.json was missing or unusable. */
        val skipped: List<String>
    )

    /**
     * Repairs a corrupt manifest (audit P0-01): preserves the original bytes
     * byte-for-byte under [RECOVERY_DIR], then rebuilds the index from
     * library/docs folders — every folder whose meta.json parses is
     * re-registered with a fresh file list (path/size/sha256 re-read from
     * disk). The rebuilt manifest is committed atomically only after the scan
     * completes; on any failure the original corrupt file is still in place.
     */
    suspend fun repairManifest(): ManifestRepairResult = mutexFor(K_MANIFEST).withLock {
        val state = readManifestStateLocked()
        check(state is ReadResult.Corrupt) {
            "Library manifest is ${state}; nothing to repair"
        }
        val preservedPath = preserveCorruptCopy(state.path)

        val rebuilt = mutableListOf<ManifestDoc>()
        val skipped = mutableListOf<String>()
        for (slug in store.list("library/docs").sorted()) {
            val meta = readOrphanMeta(slug)
            if (meta == null) {
                skipped += slug
                continue
            }
            val files = walkFiles("library/docs/$slug").map { path ->
                val bytes = store.read(path)
                ManifestFile(path, bytes.size.toLong(), sha256Hex(bytes))
            }
            rebuilt += ManifestDoc(
                id = meta.id,
                slug = slug,
                title = meta.title,
                contentHash = meta.contentHash,
                updatedAt = nowIso(),
                files = files
            )
        }

        // The corrupt file's libraryId is unrecoverable; a fresh one keeps the
        // schema valid (cloud sync treats it as a new library identity).
        val manifest = Manifest(
            libraryId = uuidProvider(),
            updatedAt = nowIso(),
            docs = rebuilt
        )
        store.write(MANIFEST_PATH, json.encodeToString(manifest))
        ManifestRepairResult(preservedPath, rebuilt.size, skipped)
    }

    /**
     * Copies an unreadable file byte-for-byte into [RECOVERY_DIR] before any
     * repair touches it. Caller must hold a mutex covering [path] (recovery
     * writes are rare; we reuse the underlying atomic store write).
     */
    private suspend fun preserveCorruptCopy(path: String): String {
        val bytes = store.read(path)
        val stamp = nowIso().replace(":", "-").replace(".", "-")
        val dest = "$RECOVERY_DIR/${path.substringAfterLast('/')}-corrupt-$stamp"
        store.write(dest, bytes)
        return dest
    }

    suspend fun findDuplicate(contentHash: String): ManifestDoc? =
        getManifest().docs.firstOrNull { it.contentHash == contentHash }

    /**
     * Commits an imported document with the exact Section 4 write order.
     */
    suspend fun addDocument(importResult: ImportResult): DocMeta = withLocks(K_MANIFEST) {
        addDocumentLocked(importResult)
    }

    /**
     * Body of [addDocument]; the caller must already hold K_MANIFEST (or the
     * multi-key set in [replaceDocument]).
     */
    private suspend fun addDocumentLocked(importResult: ImportResult): DocMeta {
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

        return meta
    }

    /**
     * Duplicate-import "Replace" (audit P1-04): imports the new file and moves
     * the old copy's user data onto it — highlights/notes, reading progress and
     * collection membership — before the old document entry/files are removed.
     *
     * Safe failure order: the new document is committed first (write order);
     * user state is migrated while the old copy still exists; the old folder is
     * removed last. If any step fails, both copies remain usable and the caller
     * reports the failure instead of losing notes silently.
     */
    suspend fun replaceDocument(
        existingId: String,
        importResult: ImportResult
    ): DocMeta = withLocks(K_MANIFEST, K_PROGRESS, K_ANNOTATIONS, K_COLLECTIONS) {
        val manifest = readOrCreateManifest()
        val oldDoc = manifest.docs.firstOrNull { it.id == existingId }
            ?: throw IllegalArgumentException("Document not found in manifest: $existingId")
        val oldMeta = json.decodeFromString<DocMeta>(
            store.readText("library/docs/${oldDoc.slug}/meta.json")
        )

        val newMeta = addDocumentLocked(
            importResult.copy(
                meta = importResult.meta.copy(collectionIds = oldMeta.collectionIds)
            )
        )

        // Annotations: same ids, re-pointed at the surviving document.
        val annotations = readAnnotationsStrictLocked()
        if (annotations != null) {
            val moved = annotations.map { a ->
                if (a.docId == existingId) a.copy(docId = newMeta.id) else a
            }
            if (moved != annotations) {
                store.write(ANNOTATIONS_PATH, json.encodeToString(moved))
            }
        }

        // Reading progress: move the entry (keep the user's position).
        val progress = readProgressStrictLocked()
        val oldProgress = progress?.get(existingId)
        if (progress != null && oldProgress != null) {
            store.write(
                PROGRESS_PATH,
                json.encodeToString(
                    progress - existingId + (newMeta.id to oldProgress.copy(docId = newMeta.id))
                )
            )
        }

        // Collections: swap the membership id, keep the collection itself.
        val collections = readCollectionsStrictLocked()
        if (collections != null) {
            val moved = collections.map { c ->
                if (existingId in c.docIds) {
                    c.copy(docIds = c.docIds - existingId + newMeta.id)
                } else c
            }
            if (moved != collections) {
                store.write(COLLECTIONS_PATH, json.encodeToString(moved))
            }
        }

        // Remove the old reading files only after user data has moved.
        store.remove("library/docs/${oldDoc.slug}", recursive = true)
        if (store.exists("library/index/text/$existingId.json")) {
            store.remove("library/index/text/$existingId.json")
        }

        // Manifest LAST: drop the old entry (the new one was committed above).
        val after = readOrCreateManifest()
        store.write(
            MANIFEST_PATH,
            json.encodeToString(
                after.copy(docs = after.docs.filter { it.id != existingId }, updatedAt = nowIso())
            )
        )
        newMeta
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
     * Result of [deleteDocument]. [manifestRemoved] is the commit point; any
     * non-fatal cleanup step that had to be skipped is reported in
     * [cleanupWarnings] with the affected data preserved, so the caller can
     * show an honest result instead of an unconditional "deleted".
     */
    data class DeleteResult(
        val manifestRemoved: Boolean,
        val cleanupWarnings: List<String>
    )

    /**
     * Result of [deleteCollection]: the collection itself is always removed
     * atomically with the collections write; [failedSlugs] lists documents
     * whose metadata cleanup could not be completed (they keep a stale id until
     * the next reconciliation/retry).
     */
    data class CollectionDeleteResult(
        val cleanedDocuments: Int,
        val failedSlugs: List<String>
    )

    /**
     * Deletes a document and every trace of it, then rewrites the manifest last
     * (spec §4 Rule 5). Holds the union of the manifest + user-file locks so a
     * concurrent saveProgress/saveAnnotation/saveCollections cannot resurrect
     * the deleted facts into a fresh JSON write (audit EPUB-005).
     *
     * Data-safety contract (audits P0-01/P1-01 — no more best-effort-silently-
     * succeed): throws [LibraryFileCorruptException] on an undecodable manifest
     * (deletion must never run against a bogus empty view) and
     * [IllegalArgumentException] for unknown ids. User-state cleanup runs
     * BEFORE the folder removal and the manifest commit:
     *  - unreadable user-state files are left byte-for-byte preserved and the
     *    skip is reported in [cleanupWarnings];
     *  - a folder-removal failure aborts BEFORE the manifest rewrite, so the
     *    library entry always keeps its normal UI route; retries are idempotent.
     */
    suspend fun deleteDocument(id: String): DeleteResult =
        withLocks(K_MANIFEST, K_PROGRESS, K_ANNOTATIONS, K_COLLECTIONS) {
            val manifest = readOrCreateManifest()
            val mDoc = manifest.docs.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Document not found in manifest: $id")
            val docDir = "library/docs/${mDoc.slug}"
            val warnings = mutableListOf<String>()

            // 1. Search text
            try {
                if (store.exists("library/index/text/$id.json")) {
                    store.remove("library/index/text/$id.json")
                }
            } catch (_: Exception) {
                warnings += "search index"
            }

            // 2. Progress — an unreadable progress.json is preserved unchanged.
            try {
                val map = readProgressStrictLocked()
                if (map != null && map.containsKey(id)) {
                    store.write(PROGRESS_PATH, json.encodeToString(map - id))
                }
            } catch (corrupt: LibraryFileCorruptException) {
                warnings += "reading progress (unreadable file preserved)"
            } catch (_: Exception) {
                warnings += "reading progress"
            }

            // 3. Annotations
            try {
                val all = readAnnotationsStrictLocked()
                if (all != null) {
                    val without = all.filter { it.docId != id }
                    if (without.size != all.size) {
                        store.write(ANNOTATIONS_PATH, json.encodeToString(without))
                    }
                }
            } catch (corrupt: LibraryFileCorruptException) {
                warnings += "notes and highlights (unreadable file preserved)"
            } catch (_: Exception) {
                warnings += "notes and highlights"
            }

            // 4. Collections membership
            try {
                val all = readCollectionsStrictLocked()
                if (all != null) {
                    var changed = false
                    val updated = all.map { c ->
                        if (id in c.docIds) {
                            changed = true
                            c.copy(docIds = c.docIds - id)
                        } else c
                    }
                    if (changed) store.write(COLLECTIONS_PATH, json.encodeToString(updated))
                }
            } catch (corrupt: LibraryFileCorruptException) {
                warnings += "collection membership (unreadable file preserved)"
            } catch (_: Exception) {
                warnings += "collection membership"
            }

            // 5. Remove folder — a failure aborts BEFORE the manifest rewrite
            //    (route to the entry stays intact for a retry), not after.
            try {
                store.remove(docDir, recursive = true)
            } catch (e: Exception) {
                return@withLocks DeleteResult(
                    false,
                    warnings + "book files (folder removal failed; the entry was kept)"
                )
            }

            // 6. Manifest last
            val now = nowIso()
            store.write(MANIFEST_PATH, json.encodeToString(manifest.copy(docs = manifest.docs - mDoc, updatedAt = now)))
            DeleteResult(true, warnings)
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

    /**
     * Writes reading progress. Data-safety (audit P0-02): an unreadable
     * existing progress.json throws [LibraryFileCorruptException] instead of
     * silently replacing the unreadable map with a one-entry file.
     */
    suspend fun saveProgress(progress: Progress) = mutexFor(K_PROGRESS).withLock {
        val map = readProgressStrictLocked() ?: emptyMap()
        store.write(PROGRESS_PATH, json.encodeToString(map + (progress.docId to progress)))
    }

    private suspend fun readProgressStrictLocked(): Map<String, Progress>? {
        if (!store.exists(PROGRESS_PATH)) return null // absent = valid empty
        return try {
            json.decodeFromString<Map<String, Progress>>(store.readText(PROGRESS_PATH))
        } catch (e: Exception) {
            throw LibraryFileCorruptException(PROGRESS_PATH, e.message ?: e.toString(), e)
        }
    }

    /**
     * Typed read of progress.json: Missing (no positions yet) vs Corrupt
     * (file exists but cannot be decoded) vs Loaded (audit P0-02).
     */
    suspend fun readAllProgressState(): ReadResult<Map<String, Progress>> {
        if (!store.exists(PROGRESS_PATH)) return ReadResult.Missing
        return try {
            ReadResult.Loaded(json.decodeFromString<Map<String, Progress>>(store.readText(PROGRESS_PATH)))
        } catch (e: Exception) {
            ReadResult.Corrupt(PROGRESS_PATH, e.message ?: e.toString())
        }
    }

    /** Tolerant map read for merge/backup flows; corrupt collapses to empty (logged). */
    suspend fun getAllProgress(): Map<String, Progress> = when (val r = readAllProgressState()) {
        is ReadResult.Loaded -> r.value
        is ReadResult.Missing -> emptyMap()
        is ReadResult.Corrupt -> {
            android.util.Log.w("folio-userstate", "${r.path} unreadable: ${r.message}")
            emptyMap()
        }
    }

    /** Tolerant single-doc read (reader resume); corrupt collapses to null (logged). */
    suspend fun getProgress(docId: String): Progress? = when (val r = readAllProgressState()) {
        is ReadResult.Loaded -> r.value[docId]
        is ReadResult.Missing -> null
        is ReadResult.Corrupt -> {
            android.util.Log.w("folio-userstate", "${r.path} unreadable: ${r.message}")
            null
        }
    }

    // --- Annotations ---

    /**
     * Writes an annotation. Data-safety (audit P0-02): an unreadable
     * annotations.json throws [LibraryFileCorruptException] instead of
     * silently replacing the unreadable list with a one-item list.
     */
    suspend fun saveAnnotation(annotation: Annotation) = mutexFor(K_ANNOTATIONS).withLock {
        val all = readAnnotationsStrictLocked() ?: emptyList()
        val without = all.filter { it.id != annotation.id }
        store.write(ANNOTATIONS_PATH, json.encodeToString(without + annotation))
    }

    /**
     * Removes an annotation. A corrupt annotations.json throws instead of
     * (previously) silently doing nothing.
     */
    suspend fun removeAnnotation(id: String): Unit = mutexFor(K_ANNOTATIONS).withLock {
        val all = readAnnotationsStrictLocked() ?: return@withLock
        val without = all.filter { it.id != id }
        if (without.size != all.size) {
            store.write(ANNOTATIONS_PATH, json.encodeToString(without))
        }
    }

    private suspend fun readAnnotationsStrictLocked(): List<Annotation>? {
        if (!store.exists(ANNOTATIONS_PATH)) return null
        return try {
            json.decodeFromString<List<Annotation>>(store.readText(ANNOTATIONS_PATH))
        } catch (e: Exception) {
            throw LibraryFileCorruptException(ANNOTATIONS_PATH, e.message ?: e.toString(), e)
        }
    }

    /**
     * Typed read of the annotations file so UIs can distinguish "nothing saved
     * yet" from "saved notes cannot be read" (audit P0-02) — a corrupt file
     * must never surface as the empty state.
     */
    suspend fun readAnnotationsState(docId: String? = null): ReadResult<List<Annotation>> {
        if (!store.exists(ANNOTATIONS_PATH)) return ReadResult.Missing
        return try {
            val all = json.decodeFromString<List<Annotation>>(store.readText(ANNOTATIONS_PATH))
            ReadResult.Loaded(docId?.let { filter -> all.filter { it.docId == filter } } ?: all)
        } catch (e: Exception) {
            ReadResult.Corrupt(ANNOTATIONS_PATH, e.message ?: e.toString())
        }
    }

    /** Tolerant list read for merge/backup flows; corrupt collapses to empty (logged). */
    suspend fun getAnnotations(docId: String? = null): List<Annotation> =
        when (val r = readAnnotationsState(docId)) {
            is ReadResult.Loaded -> r.value
            is ReadResult.Missing -> emptyList()
            is ReadResult.Corrupt -> {
                android.util.Log.w("folio-userstate", "${r.path} unreadable: ${r.message}")
                emptyList()
            }
        }

    // --- Collections ---

    /**
     * Typed read of collections.json: Missing (no collections yet) vs Corrupt
     * (file exists but cannot be decoded) vs Loaded (audit P0-02).
     */
    suspend fun readCollectionsState(): ReadResult<List<Collection>> {
        if (!store.exists(COLLECTIONS_PATH)) return ReadResult.Missing
        return try {
            ReadResult.Loaded(json.decodeFromString<List<Collection>>(store.readText(COLLECTIONS_PATH)))
        } catch (e: Exception) {
            ReadResult.Corrupt(COLLECTIONS_PATH, e.message ?: e.toString())
        }
    }

    /** Tolerant list read for merge/backup flows; corrupt collapses to empty (logged). */
    suspend fun getCollections(): List<Collection> = when (val r = readCollectionsState()) {
        is ReadResult.Loaded -> r.value
        is ReadResult.Missing -> emptyList()
        is ReadResult.Corrupt -> {
            // Never silent: a corrupt collections.json must not masquerade as
            // "no collections" in the details dialog (audit P0-02).
            android.util.Log.w("folio-collections", "${r.path} unreadable: ${r.message}")
            emptyList()
        }
    }

    private suspend fun readCollectionsStrictLocked(): List<Collection>? {
        if (!store.exists(COLLECTIONS_PATH)) return null
        return try {
            json.decodeFromString<List<Collection>>(store.readText(COLLECTIONS_PATH))
        } catch (e: Exception) {
            throw LibraryFileCorruptException(COLLECTIONS_PATH, e.message ?: e.toString(), e)
        }
    }

    suspend fun saveCollections(collections: List<Collection>): Unit =
        mutexFor(K_COLLECTIONS).withLock {
            store.write(COLLECTIONS_PATH, json.encodeToString(collections))
        }

    /**
     * Creates a collection (PWA parity: fresh id, empty docIds, now).
     * Data-safety: a corrupt collections.json blocks the write instead of
     * collapsing the unreadable list to one collection (audit P0-02).
     */
    suspend fun createCollection(name: String): Collection = mutexFor(K_COLLECTIONS).withLock {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Collection name cannot be blank" }
        val coll = Collection(
            id = uuidProvider(),
            name = clean,
            docIds = emptyList(),
            createdAt = nowIso(),
            updatedAt = nowIso()
        )
        val all = readCollectionsStrictLocked() ?: emptyList()
        store.write(COLLECTIONS_PATH, json.encodeToString(all + coll))
        coll
    }

    /** Renames a collection; throws when the id is unknown (PWA parity). */
    suspend fun renameCollection(id: String, name: String): Collection =
        mutexFor(K_COLLECTIONS).withLock {
            val collections = readCollectionsStrictLocked() ?: emptyList()
            val coll = collections.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Collection not found: $id")
            val renamed = coll.copy(name = name.trim(), updatedAt = nowIso())
            store.write(
                COLLECTIONS_PATH,
                json.encodeToString(collections.map { if (it.id == id) renamed else it })
            )
            renamed
        }

    /**
     * Deletes a collection and clears its ID from every affected document's
     * metadata (audit P1-15), so `collections.json` stays the single source of
     * truth and no book keeps a dangling collection reference.
     *
     * Holds the collections + manifest locks together; `readOrCreateManifest`
     * and `updateDocMetaLocked` are the lock-free variants (Kotlin Mutex is not
     * re-entrant). A corrupt manifest/collections file blocks the delete like
     * every other mutation. Per-document failures are reported through
     * [CollectionDeleteResult] instead of silently leaving stale ids.
     */
    suspend fun deleteCollection(id: String): CollectionDeleteResult =
        withLocks(K_COLLECTIONS, K_MANIFEST) {
            val collections = readCollectionsStrictLocked() ?: emptyList()
            if (collections.none { it.id == id }) {
                throw IllegalArgumentException("Collection not found: $id")
            }
            val manifest = readOrCreateManifest()
            store.write(COLLECTIONS_PATH, json.encodeToString(collections.filter { it.id != id }))

            var cleaned = 0
            val failed = mutableListOf<String>()
            for (doc in manifest.docs) {
                val metaPath = "library/docs/${doc.slug}/meta.json"
                val current = runCatching {
                    json.decodeFromString<DocMeta>(store.readText(metaPath))
                }.getOrNull() ?: continue
                if (id !in current.collectionIds) continue
                try {
                    updateDocMetaLocked(
                        doc.id,
                        collectionIds = current.collectionIds.filter { it != id }
                    )
                    cleaned++
                } catch (e: Exception) {
                    failed += doc.slug
                    android.util.Log.w(
                        "folio-collections",
                        "collection id cleanup skipped for ${doc.slug}",
                        e
                    )
                }
            }
            CollectionDeleteResult(cleanedDocuments = cleaned, failedSlugs = failed)
        }

    /**
     * Bulk-membership writes (collections screen): change one collection's
     * docIds in one atomic write, then re-derive every *changed* document's
     * meta.collectionIds from the full collection list (dual-write invariant
     * kept, spec §5). Really-changed docs only — membership identities are
     * preserved and updatedAt bumps so the UI can sort by recent activity.
     */
    suspend fun addDocsToCollection(collectionId: String, docIds: List<String>): Collection =
        mutateCollectionDocs(collectionId) { (it + docIds).distinct() }

    suspend fun removeDocsFromCollection(collectionId: String, docIds: List<String>): Collection =
        mutateCollectionDocs(collectionId) { it - docIds.toSet() }

    private suspend fun mutateCollectionDocs(
        collectionId: String,
        transform: (List<String>) -> List<String>
    ): Collection = withLocks(K_COLLECTIONS, K_MANIFEST) {
        val collections = readCollectionsStrictLocked() ?: emptyList()
        val coll = collections.firstOrNull { it.id == collectionId }
            ?: throw IllegalArgumentException("Collection not found: $collectionId")
        val newDocIds = transform(coll.docIds)
        if (newDocIds == coll.docIds) return@withLocks coll

        val updated = coll.copy(docIds = newDocIds, updatedAt = nowIso())
        store.write(
            COLLECTIONS_PATH,
            json.encodeToString(collections.map { if (it.id == collectionId) updated else it })
        )
        // Only docs whose membership actually changed: added or dropped.
        val changedDocIds = ((newDocIds - coll.docIds.toSet()) +
            (coll.docIds - newDocIds.toSet())).toSet()
        syncDocMembershipLocked(collections.map { if (it.id == collectionId) updated else it }, changedDocIds)
        updated
    }

    /**
     * Re-derives doc meta collectionIds from the given collection list for the
     * changed docs. Assumes K_COLLECTIONS + K_MANIFEST are held. A doc id in
     * collections.json that is not in the manifest (stale hand-edited data)
     * must not abort the whole sync — logged and skipped.
     */
    private suspend fun syncDocMembershipLocked(
        collections: List<Collection>,
        changedDocIds: Set<String>
    ) {
        val byDoc = collections.flatMap { c -> c.docIds.map { it to c.id } }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })
        for (docId in changedDocIds) {
            try {
                updateDocMetaLocked(docId, collectionIds = byDoc[docId].orEmpty())
            } catch (e: Exception) {
                android.util.Log.w(
                    "folio-collections",
                    "membership sync skipped for missing doc $docId",
                    e
                )
            }
        }
    }

    /**
     * Assigns a document to exactly [collectionIds] (spec §5): syncs membership
     * in every collection file, then the doc's meta/manifest/search text.
     * (Reads of collections inside the held lock are fine; reads are lockless
     * since AppFileStore's atomic rename never exposes torn files.)
     */
    suspend fun setDocCollections(docId: String, collectionIds: List<String>): Unit =
        withLocks(K_COLLECTIONS, K_MANIFEST) {
            val collections = readCollectionsStrictLocked() ?: emptyList()
            val updates = collections.map { coll ->
                val has = docId in coll.docIds
                val shouldHave = coll.id in collectionIds
                when {
                    shouldHave && !has -> coll.copy(docIds = coll.docIds + docId)
                    !shouldHave && has -> coll.copy(docIds = coll.docIds - docId)
                    else -> coll
                }
            }
            store.write(COLLECTIONS_PATH, json.encodeToString(updates))
            updateDocMetaLocked(docId, collectionIds = collectionIds)
        }

    /**
     * Recovery action for an unreadable user-state file (audit P0-02): the
     * original bytes are preserved byte-for-byte under [RECOVERY_DIR], then the
     * file is removed so ordinary saves can rebuild it from scratch. Readable
     * files are refused — this never discards healthy data, and it is the only
     * sanctioned way to clear a corrupt user file (plain saves throw instead).
     */
    suspend fun discardCorruptUserState(path: String): String {
        val lockKey = when (path) {
            PROGRESS_PATH -> FileLock.PROGRESS
            ANNOTATIONS_PATH -> FileLock.ANNOTATIONS
            COLLECTIONS_PATH -> FileLock.COLLECTIONS
            else -> throw IllegalArgumentException("Not a user-state file: $path")
        }
        return mutexFor(lockKey.key).withLock {
            if (!store.exists(path)) {
                throw IllegalStateException("No file to discard: $path")
            }
            // Refuse readable files: recovery only ever clears unreadable bytes.
            val decodable = when (path) {
                PROGRESS_PATH ->
                    runCatching {
                        json.decodeFromString<Map<String, Progress>>(store.readText(path))
                    }.isSuccess
                ANNOTATIONS_PATH ->
                    runCatching {
                        json.decodeFromString<List<Annotation>>(store.readText(path))
                    }.isSuccess
                else ->
                    runCatching {
                        json.decodeFromString<List<Collection>>(store.readText(path))
                    }.isSuccess
            }
            if (decodable) {
                throw IllegalStateException("File is readable — nothing to discard: $path")
            }
            val preserved = preserveCorruptCopy(path)
            store.remove(path)
            preserved
        }
    }
}
