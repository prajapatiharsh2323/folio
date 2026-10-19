package dev.folio.mobile.core.sync

import dev.folio.mobile.core.backup.parseAnnotations
import dev.folio.mobile.core.backup.parseCollections
import dev.folio.mobile.core.backup.parseManifest
import dev.folio.mobile.core.backup.parseProgressMap
import dev.folio.mobile.core.importer.ImportDispatch
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.LibraryManager.FileLock
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.Manifest
import dev.folio.mobile.core.model.ManifestDoc
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.store.FileStore
import dev.folio.mobile.core.util.sha256Hex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The sync algorithm, ported from packages/core/src/sync/sync-manager.ts.
 *
 * One sync is bidirectional and converges two devices on the same library:
 *  1. list the mirror and read the remote manifest;
 *  2. diff local and remote documents by content hash, then by id;
 *  3. download what is missing (and newer metadata that is remote);
 *  4. upload what is missing (and newer metadata that is local);
 *  5. reconcile annotations, reading progress and collections (newer wins,
 *     document ids remapped when the same book was imported independently);
 *  6. write the merged manifest locally and upload it LAST, so the mirror
 *     never advertises a half-synced library;
 *  7. persist the local sync state.
 *
 * Version-one rule: deletions never propagate to the mirror.
 */
class SyncManager(
    private val store: FileStore,
    private val library: LibraryManager,
    private val provider: SyncProvider,
    private val onProgress: ((SyncProgress) -> Unit)? = null,
) {

    companion object {
        /** Local sync state (never uploaded) — path -> drive id + hashes. */
        const val SYNC_STATE_PATH = "sync/state.json"

        const val MANIFEST_PATH = "library/manifest.json"
        const val ANNOTATIONS_PATH = "library/user/annotations.json"
        const val PROGRESS_PATH = "library/user/progress.json"
        const val COLLECTIONS_PATH = "library/user/collections.json"

        /**
         * 2-space pretty printing with omitted nulls — byte-identical to the
         * PWA's `JSON.stringify(value, null, 2)`, so both apps produce the
         * same bytes for the same data and don't re-upload each other's work.
         */
        private val syncJson = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            prettyPrintIndent = "  "
            explicitNulls = false
        }
    }

    /** Reads the local sync state; a missing or broken file means "nothing yet". */
    suspend fun loadState(): SyncState {
        if (!store.exists(SYNC_STATE_PATH)) return SyncState()
        return runCatching {
            syncJson.decodeFromString(SyncState.serializer(), store.readText(SYNC_STATE_PATH))
        }.getOrElse { SyncState() }
    }

    /** Persists the local sync state. */
    suspend fun saveState(state: SyncState) {
        store.write(SYNC_STATE_PATH, syncJson.encodeToString(SyncState.serializer(), state))
    }

    /** Runs one full sync cycle. */
    suspend fun sync(): SyncResult {
        val result = SyncResult()
        report("connecting", 0, 100, message = "Connecting to Google Drive")

        report("listing", 5, 100, message = "Listing remote files")
        val remoteFiles = provider.list()
        val remoteMap = remoteFiles.associateBy { it.path }
        val stateFiles = HashMap<String, SyncStateItem>()
        loadState().files.forEach { (path, item) -> stateFiles[path] = item }

        val remoteManifest: Manifest? = remoteMap[MANIFEST_PATH]?.let {
            runCatching { parseManifest(provider.download(MANIFEST_PATH).decodeToString()) }.getOrNull()
        }

        val localManifest = library.getManifest()

        // --- Diff -----------------------------------------------------------
        val localByHash = localManifest.docs.associateBy { it.contentHash }
        val localById = localManifest.docs.associateBy { it.id }
        val remoteByHash = remoteManifest?.docs?.associateBy { it.contentHash } ?: emptyMap()
        val remoteById = remoteManifest?.docs?.associateBy { it.id } ?: emptyMap()

        /** remote document id -> local document id (same book, different import) */
        val docIdRemap = HashMap<String, String>()
        val docsToUpload = mutableListOf<ManifestDoc>()
        val docMetasToUpload = mutableListOf<ManifestDoc>()
        val docsToDownload = mutableListOf<ManifestDoc>()
        val docMetasToDownload = mutableListOf<ManifestDoc>()

        for (localDoc in localManifest.docs) {
            val match = remoteByHash[localDoc.contentHash] ?: remoteById[localDoc.id]
            if (match == null) {
                docsToUpload.add(localDoc)
            } else {
                if (match.id != localDoc.id) docIdRemap[match.id] = localDoc.id
                if (epochMs(localDoc.updatedAt) > epochMs(match.updatedAt)) {
                    docMetasToUpload.add(localDoc)
                }
            }
        }
        remoteManifest?.docs?.forEach { remoteDoc ->
            val match = localByHash[remoteDoc.contentHash] ?: localById[remoteDoc.id]
            if (match == null) {
                docsToDownload.add(remoteDoc)
            } else {
                if (match.id != remoteDoc.id) docIdRemap[remoteDoc.id] = match.id
                if (epochMs(remoteDoc.updatedAt) > epochMs(match.updatedAt)) {
                    docMetasToDownload.add(remoteDoc)
                }
            }
        }

        // --- Download -------------------------------------------------------
        val totalDownloads = docsToDownload.size + docMetasToDownload.size
        var downloadIndex = 0
        for (remoteDoc in docsToDownload) {
            downloadIndex++
            report(
                "downloading", downloadIndex, totalDownloads.coerceAtLeast(1),
                currentItem = remoteDoc.title,
                message = "Downloading ${remoteDoc.title}",
            )
            for (file in remoteDoc.files) {
                if (!remoteMap.containsKey(file.path)) continue
                val bytes = provider.download(file.path)
                store.write(file.path, bytes)
                rememberFile(stateFiles, file.path, remoteMap[file.path])
                result.downloadedFiles++
            }
            val metaPath = metaPathFor(remoteDoc)
            if (remoteMap.containsKey(metaPath)) {
                val bytes = provider.download(metaPath)
                store.write(metaPath, bytes)
                rememberFile(stateFiles, metaPath, remoteMap[metaPath])
                result.downloadedFiles++
            }
            rebuildSearchText(remoteDoc)
            result.downloadedDocs++
        }
        for (remoteDoc in docMetasToDownload) {
            downloadIndex++
            report(
                "downloading", downloadIndex, totalDownloads.coerceAtLeast(1),
                currentItem = remoteDoc.title,
                message = "Updating ${remoteDoc.title}",
            )
            val metaPath = metaPathFor(remoteDoc)
            if (remoteMap.containsKey(metaPath)) {
                val bytes = provider.download(metaPath)
                store.write(metaPath, bytes)
                rememberFile(stateFiles, metaPath, remoteMap[metaPath])
                result.downloadedFiles++
            }
        }

        // --- Upload ---------------------------------------------------------
        val totalUploads = docsToUpload.size + docMetasToUpload.size
        var uploadIndex = 0
        for (localDoc in docsToUpload) {
            uploadIndex++
            report(
                "uploading", uploadIndex, totalUploads.coerceAtLeast(1),
                currentItem = localDoc.title,
                message = "Uploading ${localDoc.title}",
            )
            for (file in localDoc.files) {
                if (file.path == metaPathFor(localDoc)) continue // uploaded below
                if (!store.exists(file.path)) continue
                val sha = sha256Hex(store.read(file.path))
                val id = uploadFile(file.path, sha)
                rememberUpload(stateFiles, file.path, id, sha)
                result.uploadedFiles++
            }
            val metaPath = metaPathFor(localDoc)
            if (store.exists(metaPath)) {
                val metaBytes = store.read(metaPath)
                val sha = sha256Hex(metaBytes)
                val id = uploadFile(metaPath, sha)
                rememberUpload(stateFiles, metaPath, id, sha)
                result.uploadedFiles++
            }
            result.uploadedDocs++
        }
        for (localDoc in docMetasToUpload) {
            uploadIndex++
            report(
                "uploading", uploadIndex, totalUploads.coerceAtLeast(1),
                currentItem = localDoc.title,
                message = "Uploading updated ${localDoc.title}",
            )
            val metaPath = metaPathFor(localDoc)
            if (store.exists(metaPath)) {
                val metaBytes = store.read(metaPath)
                val sha = sha256Hex(metaBytes)
                val id = uploadFile(metaPath, sha)
                rememberUpload(stateFiles, metaPath, id, sha)
                result.uploadedFiles++
            }
        }

        // --- Reconcile user data -------------------------------------------
        report("merging", 75, 100, message = "Reconciling notes, progress and collections")
        reconcileAnnotations(remoteMap, stateFiles, result, docIdRemap)
        reconcileProgress(remoteMap, stateFiles, result, docIdRemap)
        reconcileCollections(remoteMap, stateFiles, result, docIdRemap)

        // --- Merge the manifest and upload it LAST -------------------------
        val mergedByHash = LinkedHashMap<String, ManifestDoc>()
        localManifest.docs.forEach { mergedByHash[it.contentHash] = it }
        docsToDownload.forEach { doc ->
            if (!mergedByHash.containsKey(doc.contentHash)) mergedByHash[doc.contentHash] = doc
        }
        docMetasToDownload.forEach { doc ->
            val existing = mergedByHash[doc.contentHash]
            if (existing != null) {
                mergedByHash[doc.contentHash] = existing.copy(title = doc.title, updatedAt = doc.updatedAt)
            }
        }

        val finalManifest = Manifest(
            schema = 1,
            app = "folio",
            libraryId = localManifest.libraryId.ifBlank {
                remoteManifest?.libraryId ?: java.util.UUID.randomUUID().toString()
            },
            updatedAt = nowIso(),
            docs = mergedByHash.values.toList(),
        )
        val manifestText = syncJson.encodeToString(Manifest.serializer(), finalManifest)
        library.lockFiles(FileLock.MANIFEST) {
            store.write(MANIFEST_PATH, manifestText)
        }

        report("uploading", 95, 100, currentItem = MANIFEST_PATH, message = "Uploading library manifest")
        val manifestBytes = manifestText.encodeToByteArray()
        val manifestSha = sha256Hex(manifestBytes)
        val manifestId = uploadFile(MANIFEST_PATH, manifestSha)
        rememberUpload(stateFiles, MANIFEST_PATH, manifestId, manifestSha)
        result.uploadedFiles++

        saveState(SyncState(version = 1, files = stateFiles))
        report("complete", 100, 100, message = "Sync complete")
        return result
    }

    // --- user data reconciliation -----------------------------------------

    private suspend fun reconcileAnnotations(
        remoteMap: Map<String, SyncFileEntry>,
        stateFiles: MutableMap<String, SyncStateItem>,
        result: SyncResult,
        docIdRemap: Map<String, String>,
    ) {
        val hasRemote = remoteMap.containsKey(ANNOTATIONS_PATH)
        val hasLocal = store.exists(ANNOTATIONS_PATH)
        if (!hasRemote && !hasLocal) return

        val remoteAnnotations = if (hasRemote) {
            runCatching {
                parseAnnotations(provider.download(ANNOTATIONS_PATH).decodeToString())
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val merged = LinkedHashMap<String, Annotation>()
        library.getAnnotations().forEach { merged[it.id] = it }
        for (remote in remoteAnnotations) {
            val normalized = remote.copy(docId = docIdRemap[remote.docId] ?: remote.docId)
            val current = merged[normalized.id]
            if (current == null || epochMs(normalized.updatedAt) > epochMs(current.updatedAt)) {
                merged[normalized.id] = normalized
            }
        }

        val bytes = syncJson.encodeToString(ListSerializer(Annotation.serializer()), merged.values.toList())
            .encodeToByteArray()
        library.lockFiles(FileLock.ANNOTATIONS) {
            store.write(ANNOTATIONS_PATH, bytes)
        }
        uploadIfChanged(ANNOTATIONS_PATH, bytes, remoteMap, stateFiles, result)
    }

    private suspend fun reconcileProgress(
        remoteMap: Map<String, SyncFileEntry>,
        stateFiles: MutableMap<String, SyncStateItem>,
        result: SyncResult,
        docIdRemap: Map<String, String>,
    ) {
        val hasRemote = remoteMap.containsKey(PROGRESS_PATH)
        val hasLocal = store.exists(PROGRESS_PATH)
        if (!hasRemote && !hasLocal) return

        val remoteProgress = if (hasRemote) {
            runCatching {
                parseProgressMap(provider.download(PROGRESS_PATH).decodeToString())
            }.getOrElse { emptyMap() }
        } else {
            emptyMap()
        }

        val merged = LinkedHashMap<String, Progress>()
        library.getAllProgress().forEach { (id, progress) -> merged[id] = progress }
        for ((rawDocId, progress) in remoteProgress) {
            val docId = docIdRemap[rawDocId] ?: rawDocId
            val normalized = progress.copy(docId = docId)
            val current = merged[docId]
            if (current == null || epochMs(normalized.updatedAt) > epochMs(current.updatedAt)) {
                merged[docId] = normalized
            }
        }

        val bytes = syncJson.encodeToString(
            MapSerializer(String.serializer(), Progress.serializer()),
            merged,
        ).encodeToByteArray()
        library.lockFiles(FileLock.PROGRESS) {
            store.write(PROGRESS_PATH, bytes)
        }
        uploadIfChanged(PROGRESS_PATH, bytes, remoteMap, stateFiles, result)
    }

    private suspend fun reconcileCollections(
        remoteMap: Map<String, SyncFileEntry>,
        stateFiles: MutableMap<String, SyncStateItem>,
        result: SyncResult,
        docIdRemap: Map<String, String>,
    ) {
        val hasRemote = remoteMap.containsKey(COLLECTIONS_PATH)
        val hasLocal = store.exists(COLLECTIONS_PATH)
        if (!hasRemote && !hasLocal) return

        val remoteCollections = if (hasRemote) {
            runCatching {
                parseCollections(provider.download(COLLECTIONS_PATH).decodeToString())
            }.getOrElse { emptyList() }
        } else {
            emptyList()
        }

        val merged = LinkedHashMap<String, Collection>()
        library.getCollections().forEach { merged[it.id] = it }
        for (remote in remoteCollections) {
            val remappedDocIds = remote.docIds.map { docIdRemap[it] ?: it }
            val current = merged[remote.id]
            merged[remote.id] = if (current == null) {
                remote.copy(docIds = remappedDocIds)
            } else {
                current.copy(
                    name = current.name.ifBlank { remote.name },
                    createdAt = current.createdAt.ifBlank { remote.createdAt },
                    docIds = (current.docIds + remappedDocIds).distinct(),
                )
            }
        }

        val bytes = syncJson.encodeToString(ListSerializer(Collection.serializer()), merged.values.toList())
            .encodeToByteArray()
        library.lockFiles(FileLock.COLLECTIONS) {
            store.write(COLLECTIONS_PATH, bytes)
        }
        uploadIfChanged(COLLECTIONS_PATH, bytes, remoteMap, stateFiles, result)
    }

    // --- helpers -----------------------------------------------------------

    /** Uploads [bytes] when the mirror still holds a different version. */
    private suspend fun uploadIfChanged(
        path: String,
        bytes: ByteArray,
        remoteMap: Map<String, SyncFileEntry>,
        stateFiles: MutableMap<String, SyncStateItem>,
        result: SyncResult,
    ) {
        val sha = sha256Hex(bytes)
        val remoteEntry = remoteMap[path]
        if (remoteEntry == null || remoteEntry.sha256 != sha) {
            val id = uploadFile(path, sha, bytes)
            rememberUpload(stateFiles, path, id, sha)
            result.mergedUserFiles++
        }
    }

    /** Uploads a local library file, streaming it from disk when possible. */
    private suspend fun uploadFile(path: String, sha256: String, bytes: ByteArray? = null): String {
        val localFile: File? = store.localFile(path)
        return if (localFile != null) {
            provider.uploadFile(path, localFile, sha256)
        } else {
            provider.upload(path, bytes ?: store.read(path), sha256)
        }
    }

    private fun metaPathFor(doc: ManifestDoc): String = "library/docs/${doc.slug}/meta.json"

    private fun rememberFile(
        stateFiles: MutableMap<String, SyncStateItem>,
        path: String,
        entry: SyncFileEntry?,
    ) {
        stateFiles[path] = SyncStateItem(
            driveId = entry?.id ?: "",
            sha256 = entry?.sha256 ?: "",
            remoteModifiedAt = entry?.modifiedAt ?: nowIso(),
        )
    }

    private fun rememberUpload(
        stateFiles: MutableMap<String, SyncStateItem>,
        path: String,
        driveId: String,
        sha256: String,
    ) {
        stateFiles[path] = SyncStateItem(
            driveId = driveId,
            sha256 = sha256,
            remoteModifiedAt = nowIso(),
        )
    }

    /**
     * Rebuilds the local search corpus for a freshly downloaded document.
     *
     * The search index is not mirrored (spec §8: the mirror excludes the
     * index), so a downloaded book would otherwise be invisible to search
     * until it is re-imported. Re-extracting the text from the original file
     * is cheap compared to the download itself.
     */
    private suspend fun rebuildSearchText(doc: ManifestDoc) {
        val searchPath = "library/index/text/${doc.id}.json"
        if (store.exists(searchPath)) return
        val originalPath = doc.files.firstOrNull { it.path.contains("/original/") }?.path ?: return
        if (!store.exists(originalPath)) return
        val bytes = runCatching { store.read(originalPath) }.getOrNull() ?: return
        val fileName = originalPath.substringAfterLast('/')
        val searchText = runCatching {
            ImportDispatch.import(bytes, fileName).searchText
        }.getOrNull() ?: return
        if (searchText.isEmpty()) return
        store.write(
            searchPath,
            syncJson.encodeToString(ListSerializer(SearchChapterText.serializer()), searchText),
        )
    }

    private fun report(
        phase: String,
        current: Int,
        total: Int,
        currentItem: String? = null,
        message: String = "",
    ) {
        onProgress?.invoke(SyncProgress(phase, current, total, currentItem, message))
    }

    private fun epochMs(iso: String?): Long =
        runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrElse { 0L }

    private fun nowIso(): String = java.time.Instant.now().toString()
}
