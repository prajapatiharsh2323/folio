package dev.folio.mobile.core.backup

import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.LibraryManager.FileLock
import dev.folio.mobile.core.library.MANIFEST_PATH
import dev.folio.mobile.core.library.RECOVERY_DIR
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.library.RestoreSwap
import dev.folio.mobile.core.importer.ImportDispatch
import dev.folio.mobile.core.importer.SearchChapterText
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.Manifest
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.store.FileStore
import dev.folio.mobile.core.util.IsoTime
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.InputStream

/**
 * Kotlin port of packages/core/src/backup/import-zip.ts — restores a verified
 * backup ZIP into the FileStore following the exact spec §8 merge & replace
 * rules:
 *
 * 1. Merge (default):
 *    - a document whose contentHash already exists is skipped
 *    - for the same id, the newer updatedAt metadata wins (meta.json rewritten)
 *    - annotations merge by id with the newer updatedAt winning
 *    - progress keeps the newer entry per document
 *    - collections union by id with their docIds combined
 *    - local settings are kept
 *    - merge never deletes anything
 *
 * 2. Replace: the FULL new library tree is staged first (docs, user state,
 *    manifest), verified, and then swapped in atomically with rename steps —
 *    the previous library is rolled back completely if any step fails or the
 *    process dies mid-commit (audit P0-03). The old "wipe before writing"
 *    path is gone: a failed replace can no longer leave an empty library.
 *
 * Both modes pre-parse every user-state entry (annotations, progress,
 * collections) BEFORE any local state is touched — a checksum-valid archive
 * with malformed saved data is refused outright, never silently skipped
 * (audit P0-04).
 *
 * Audit EPUB-006/007: the archive is staged on disk and streamed entry by
 * entry ([StagedArchive]) instead of being fully loaded into RAM, and the
 * mutation phase runs under the manager's file locks so concurrent
 * progress/annotation writers cannot interleave their read-modify-writes
 * with the restore (audit EPUB-005).
 */
object BackupRestore {

    private fun epochMs(iso: String?): Long = dev.folio.mobile.core.util.IsoTime.epochMs(iso)

    /** "library/docs/x" → "docs/x" (the staging tree mirrors the library root). */
    private fun stagedRel(path: String): String {
        require(path.startsWith("library/")) { "Not a library-relative path: $path" }
        return path.removePrefix("library/")
    }

    /** Staging entrypoint: streams [input] to a temp file under [stagingDir], then restores. */
    suspend fun restore(
        store: FileStore,
        manager: LibraryManager,
        input: InputStream,
        stagingDir: File,
        mode: RestoreMode
    ): Pair<RestoreSummary, VerificationResult> =
        StagedArchive.stage(input, stagingDir).use { archive ->
            restore(store, manager, archive, mode)
        }

    /** Restore from an already-staged [archive] (caller closes it). */
    suspend fun restore(
        store: FileStore,
        manager: LibraryManager,
        archive: StagedArchive,
        mode: RestoreMode
    ): Pair<RestoreSummary, VerificationResult> {
        // 0. Kill any leftover from an interrupted earlier restore first: an
        //    unfinished swap is rolled back so the live tree is always coherent
        //    before new work begins (audit P0-03 — process-death recovery).
        RestoreSwap.rollbackPending(store)

        // 1. Verify the archive thoroughly before touching local state.
        val verification = unzipAndVerifyBackup(archive)
        val fatal = verification.issues.filter {
            it.reason == "unsafe_path" || it.reason == "corrupted" ||
                    it.path == "library/manifest.json"
        }
        if (fatal.isNotEmpty()) {
            throw IllegalArgumentException(
                "Backup archive failed verification: " +
                    (fatal.joinToString("; ") { it.message }.ifBlank { "Invalid archive" })
            )
        }

        // 2. Validate the backup manifest.
        val backupManifestBytes = archive.readEntry(MANIFEST_PATH)
            ?: throw IllegalArgumentException("Missing library/manifest.json in backup archive")
        val backupManifest: Manifest = runCatching { parseManifest(backupManifestBytes.decodeToString()) }
            .getOrElse { throw IllegalArgumentException("Corrupted library/manifest.json in backup archive") }

        // 3. Pre-parse every user-state entry BEFORE any mutation (audit P0-04):
        //    a checksum-valid archive with malformed saved data is refused
        //    outright, so a Replace can never wipe the live library over data
        //    it could not actually read back.
        val parsed = parseUserState(archive)
        val (annotations, progress, collections, settingsOk, settingsBytes) = parsed

        val summary = RestoreSummary(
            mode = mode.name.lowercase(),
            totalDocsInBackup = backupManifest.docs.size,
            newDocsAdded = 0,
            duplicatesSkipped = 0,
            metadataUpdated = 0,
            annotationsMerged = 0,
            progressUpdated = 0,
            collectionsMerged = 0
        )

        // Mutation phase under the manager's file locks so a concurrent reader
        // progress/annotation save or deleteDocument cannot interleave its own
        // read-modify-write with the restore's (audit EPUB-005).
        return manager.lockFiles(
            FileLock.MANIFEST, FileLock.PROGRESS, FileLock.ANNOTATIONS, FileLock.COLLECTIONS
        ) {
            applyRestore(
                store, manager, archive, backupManifest, mode, summary, verification,
                annotations, progress, collections, settingsOk, settingsBytes
            )
        }
    }

    /**
     * Fully parsed backup user state. Every collection the restore will write
     * is available before the mutation phase (audit P0-04); [settingsOk]
     * allows a malformed optional settings file to be skipped with a warning
     * instead of silently corrupting reader preferences.
     */
    private data class ParsedUserState(
        val annotations: List<Annotation>,
        val progress: Map<String, Progress>,
        val collections: List<Collection>,
        val settingsOk: Boolean,
        val settingsBytes: ByteArray?
    )

    private fun parseUserState(archive: StagedArchive): ParsedUserState {
        val annotBytes = archive.readEntry("library/user/annotations.json")
        val progBytes = archive.readEntry("library/user/progress.json")
        val collBytes = archive.readEntry("library/user/collections.json")
        val settingsBytes = archive.readEntry("library/user/settings.json")

        val annotations = if (annotBytes != null) runCatching {
            parseAnnotations(annotBytes.decodeToString())
        }.getOrElse {
            throw IllegalArgumentException(
                "The backup's saved notes (annotations.json) can't be read — restore refused, " +
                        "your library is untouched: ${it.message}"
            )
        } else emptyList()

        val progress = if (progBytes != null) runCatching {
            parseProgressMap(progBytes.decodeToString())
        }.getOrElse {
            throw IllegalArgumentException(
                "The backup's reading progress (progress.json) can't be read — restore refused, " +
                        "your library is untouched: ${it.message}"
            )
        } else emptyMap()

        val collections = if (collBytes != null) runCatching {
            parseCollections(collBytes.decodeToString())
        }.getOrElse {
            throw IllegalArgumentException(
                "The backup's collections (collections.json) can't be read — restore refused, " +
                        "your library is untouched: ${it.message}"
            )
        } else emptyList()

        val settingsOk = settingsBytes == null || runCatching {
            backupJson.decodeFromString(ReadingPrefs.serializer(), settingsBytes.decodeToString())
        }.isSuccess

        return ParsedUserState(annotations, progress, collections, settingsOk, settingsBytes)
    }

    private suspend fun applyRestore(
        store: FileStore,
        manager: LibraryManager,
        unzipped: StagedArchive,
        backupManifest: Manifest,
        mode: RestoreMode,
        summary: RestoreSummary,
        verification: VerificationResult,
        backupAnnotations: List<Annotation>,
        backupProgress: Map<String, Progress>,
        backupCollections: List<Collection>,
        settingsOk: Boolean,
        settingsBytes: ByteArray?
    ): Pair<RestoreSummary, VerificationResult> {
        val warnings = mutableListOf<String>()

        // 4. Resolve the final manifest's libraryId before anything moves (a
        //    Replace keeps the local libraryId when the local manifest is
        //    readable; a corrupt/absent one adopts the backup's identity).
        val replaceLibraryId: String =
            if (mode != RestoreMode.Replace) "" else run {
                val state = if (store.exists(MANIFEST_PATH)) {
                    runCatching {
                        parseManifest(store.readText(MANIFEST_PATH)).libraryId
                    }.getOrNull()
                } else null
                if (state.isNullOrBlank()) backupManifest.libraryId.ifBlank { "unknown" } else state
            }

        // --- Merge path -----------------------------------------------------
        if (mode == RestoreMode.Merge) {
            val currentManifest: Manifest =
                if (store.exists(MANIFEST_PATH)) {
                    try {
                        parseManifest(store.readText(MANIFEST_PATH))
                    } catch (e: Exception) {
                        // Merge is the deliberate escape hatch for a damaged
                        // local index, but the unreadable bytes must survive
                        // before the backup's manifest replaces them.
                        preserveCorruptLocal(store, MANIFEST_PATH, warnings)
                        Manifest(
                            libraryId = backupManifest.libraryId,
                            updatedAt = IsoTime.now()
                        )
                    }
                } else {
                    Manifest(
                        libraryId = backupManifest.libraryId,
                        updatedAt = IsoTime.now()
                    )
                }

            val existingByHash = currentManifest.docs.associateBy { it.contentHash }.toMutableMap()
            val existingById = currentManifest.docs.associateBy { it.id }.toMutableMap()

            var newDocsAdded = 0
            var duplicatesSkipped = 0
            var metadataUpdated = 0
            val mergedDocs = currentManifest.docs.toMutableList()

            for (bDoc in backupManifest.docs) {
                val byHash = existingByHash[bDoc.contentHash]
                val byId = existingById[bDoc.id]

                if (byHash != null) {
                    duplicatesSkipped++
                    continue
                }
                if (byId != null) {
                    if (epochMs(bDoc.updatedAt) > epochMs(byId.updatedAt)) {
                        (unzipped.readEntry("library/docs/${bDoc.slug}/meta.json"))?.let {
                            store.write("library/docs/${bDoc.slug}/meta.json", it)
                        }
                        val idx = mergedDocs.indexOfFirst { it.id == byId.id }
                        if (idx >= 0) {
                            mergedDocs[idx] = byId.copy(title = bDoc.title, updatedAt = bDoc.updatedAt)
                        }
                        existingById[bDoc.id] = mergedDocs[idx]
                        metadataUpdated++
                    }
                    continue
                }

                // Write all files belonging to this document (meta.json included).
                for (f in bDoc.files) {
                    unzipped.readEntry(f.path)?.let { store.write(f.path, it) }
                }
                unzipped.readEntry("library/docs/${bDoc.slug}/meta.json")?.let {
                    store.write("library/docs/${bDoc.slug}/meta.json", it)
                }

                mergedDocs.add(bDoc)
                existingByHash[bDoc.contentHash] = bDoc
                existingById[bDoc.id] = bDoc
                newDocsAdded++
            }

            // Annotations: newer-wins by id; local corruption is reported.
            val annotationsResult = run {
                val localState = manager.readAnnotationsState() // strict RMW contract below
                val current = when (localState) {
                    is ReadResult.Loaded -> localState.value
                    is ReadResult.Missing -> emptyList()
                    is ReadResult.Corrupt -> {
                        preserveCorruptLocal(store, "library/user/annotations.json", warnings)
                        warnings += "Your local saved notes couldn't be read, so they were not merged — restore proceeded with the backup's notes."
                        emptyList()
                    }
                }
                val map = current.associateBy { it.id }.toMutableMap()
                var merged = 0
                for (b in backupAnnotations) {
                    val curr = map[b.id]
                    if (curr == null || epochMs(b.updatedAt) > epochMs(curr.updatedAt)) {
                        map[b.id] = b
                        merged++
                    }
                }
                store.write(
                    "library/user/annotations.json",
                    backupJson.encodeToString(ListSerializer(Annotation.serializer()), map.values.toList())
                )
                merged
            }

            // Progress: newer-wins per doc.
            val progressResult = run {
                val localState = manager.readAllProgressState()
                val current = when (localState) {
                    is ReadResult.Loaded -> localState.value
                    is ReadResult.Missing -> emptyMap()
                    is ReadResult.Corrupt -> {
                        preserveCorruptLocal(store, "library/user/progress.json", warnings)
                        warnings += "Your local reading progress couldn't be read, so it was not merged — restore proceeded with the backup's progress."
                        emptyMap<String, Progress>()
                    }
                }
                val map = current.toMutableMap()
                var updated = 0
                for ((docId, p) in backupProgress) {
                    val curr = map[docId]
                    if (curr == null || epochMs(p.updatedAt) > epochMs(curr.updatedAt)) {
                        map[docId] = p
                        updated++
                    }
                }
                store.write(
                    "library/user/progress.json",
                    backupJson.encodeToString(
                        MapSerializer(String.serializer(), Progress.serializer()),
                        map
                    )
                )
                updated
            }

            // Collections: unions docIds.
            val collectionsResult = run {
                val localState = manager.readCollectionsState()
                val current = when (localState) {
                    is ReadResult.Loaded -> localState.value
                    is ReadResult.Missing -> emptyList()
                    is ReadResult.Corrupt -> {
                        preserveCorruptLocal(store, "library/user/collections.json", warnings)
                        warnings += "Your local collections couldn't be read, so they were not merged — restore proceeded with the backup's collections."
                        emptyList()
                    }
                }
                val map = current.associateBy { it.id }.toMutableMap()
                var merged = 0
                for (b in backupCollections) {
                    val curr = map[b.id]
                    if (curr == null) {
                        map[b.id] = b
                    } else {
                        map[b.id] = curr.copy(docIds = (curr.docIds + b.docIds).distinct())
                    }
                    merged++
                }
                store.write(
                    "library/user/collections.json",
                    backupJson.encodeToString(ListSerializer(Collection.serializer()), map.values.toList())
                )
                merged
            }

            // Commit the updated manifest LAST (write-order rule).
            store.write(
                MANIFEST_PATH,
                backupJson.encodeToString(
                    Manifest.serializer(),
                    currentManifest.copy(
                        docs = mergedDocs,
                        updatedAt = IsoTime.now(),
                        libraryId = currentManifest.libraryId.ifBlank { backupManifest.libraryId }
                    )
                )
            )

            // Backups exclude library/index/text (spec §8), so restored books
            // would be invisible to search until re-imported. Rebuild the
            // missing corpora from the original files, best-effort.
            rebuildMissingSearchText(store, mergedDocs, warnings)

            return summary.copy(
                newDocsAdded = newDocsAdded,
                duplicatesSkipped = duplicatesSkipped,
                metadataUpdated = metadataUpdated,
                annotationsMerged = annotationsResult,
                progressUpdated = progressResult,
                collectionsMerged = collectionsResult,
                warnings = warnings + verification.issues.map { it.message }
            ).let { Pair(it, verification) }
        }

        // --- Replace path (staged tree + atomic swap, audit P0-03) ----------
        RestoreSwap.clearWorkDirs(store)

        try {
            val userFiles = stageReplaceTree(
                store, unzipped, backupManifest, replaceLibraryId,
                warnings, settingsOk, settingsBytes
            )

            // 6. Atomic swap — the previous library is reversed back on failure.
            RestoreSwap.commitReplace(store)

            // Post-commit verify: the live manifest parses and lists the backup docs.
            val committed = runCatching { parseManifest(store.readText(MANIFEST_PATH)) }.getOrNull()
            if (committed == null || committed.docs.size != backupManifest.docs.size) {
                throw IllegalStateException(
                    "Restore verification failed after commit — the pre-restore library was rolled back"
                )
            }

            // Backups exclude the search index (spec §8); rebuild it from the
            // original files now that the new tree is live.
            rebuildMissingSearchText(store, committed.docs, warnings)

            return summary.copy(
                newDocsAdded = backupManifest.docs.size,
                duplicatesSkipped = 0,
                metadataUpdated = 0,
                annotationsMerged = backupAnnotations.size,
                progressUpdated = backupProgress.size,
                collectionsMerged = backupCollections.size,
                warnings = warnings + verification.issues.map { it.message }
            ).let { Pair(it, verification) }
        } catch (e: Throwable) {
            // A failure before/during the swap may have left partial staging;
            // drop it. The rollback snapshot, when one exists (a mid-swap
            // failure whose reversal itself failed), must be KEPT for the next
            // recovery pass — it holds the user's pre-restore library.
            val rollbackSnapshotLeft = runCatching {
                store.list("library").contains("restore-rollback")
            }.getOrDefault(false)
            if (!rollbackSnapshotLeft) {
                runCatching { RestoreSwap.clearWorkDirs(store) }
                    .onFailure { cleanupError ->
                        android.util.Log.w("BackupRestore", "Staging cleanup failed: ${cleanupError.message}")
                    }
            }
            throw e
        }
    }

    /**
     * Builds the complete replacement library tree under [RestoreSwap.STAGING_DIR]
     * and verifies it digest-by-digest BEFORE the swap — nothing in the live
     * tree moves until staging has fully succeeded (audit P0-03). Returns the
     * map of staged, parsed-ok user-state files (for diagnostics/tests).
     */
    private suspend fun stageReplaceTree(
        store: FileStore,
        unzipped: StagedArchive,
        backupManifest: Manifest,
        replaceLibraryId: String,
        warnings: MutableList<String>,
        settingsOk: Boolean,
        settingsBytes: ByteArray?
    ): Map<String, ByteArray> {
        // 5a. Stage every document file from the archive under the staging tree.
        for (bDoc in backupManifest.docs) {
            for (f in bDoc.files) {
                val bytes = unzipped.readEntry(f.path)
                    ?: throw IllegalArgumentException(
                        "Restore stage failed: ${f.path} is listed but missing from the archive"
                    )
                store.write("${RestoreSwap.STAGING_DIR}/${stagedRel(f.path)}", bytes)
            }
        }

        // 5b. Stage the user state as RAW bytes (byte-compatible PWA contract).
        val userFiles = HashMap<String, ByteArray>()
        unzipped.readEntry("library/user/annotations.json")?.let { userFiles["library/user/annotations.json"] = it }
        unzipped.readEntry("library/user/progress.json")?.let { userFiles["library/user/progress.json"] = it }
        unzipped.readEntry("library/user/collections.json")?.let { userFiles["library/user/collections.json"] = it }
        if (settingsBytes != null && settingsOk) {
            userFiles["library/user/settings.json"] = settingsBytes
        } else if (settingsBytes != null && !settingsOk) {
            warnings += "The backup's reader settings (settings.json) are undecodable and were not restored."
        }
        for ((path, bytes) in userFiles) {
            store.write("${RestoreSwap.STAGING_DIR}/${stagedRel(path)}", bytes)
        }

        // 5c. Stage the committed manifest (Keep-or-adopt libraryId step 4).
        val replaceManifest = backupManifest.copy(
            libraryId = replaceLibraryId,
            updatedAt = java.time.Instant.now().toString()
        )
        val stagedManifestBytes = backupJson
            .encodeToString(Manifest.serializer(), replaceManifest)
            .encodeToByteArray()
        store.write("${RestoreSwap.STAGING_DIR}/${stagedRel(MANIFEST_PATH)}", stagedManifestBytes)

        // 5d. Verify the staged tree before ANYTHING in the live tree moves:
        //     every manifest-listed file exists in staging with its recorded
        //     digest (catches staging I/O mangling — audit P0-03).
        for (bDoc in backupManifest.docs) {
            for (f in bDoc.files) {
                val stagedPath = "${RestoreSwap.STAGING_DIR}/${stagedRel(f.path)}"
                val bytes = runCatching { store.read(stagedPath) }.getOrElse {
                    throw IllegalStateException(
                        "Restore stage verification failed: $stagedPath could not be re-read"
                    )
                }
                if (dev.folio.mobile.core.util.sha256Hex(bytes) != f.sha256) {
                    throw IllegalStateException(
                        "Restore stage verification failed: $stagedPath digest mismatch"
                    )
                }
            }
        }
        // The staged manifest must parse back to the exact doc count we intend.
        val stagedManifest = runCatching {
            parseManifest(store.readText("${RestoreSwap.STAGING_DIR}/${stagedRel(MANIFEST_PATH)}"))
        }.getOrNull()
        if (stagedManifest == null || stagedManifest.docs.size != backupManifest.docs.size) {
            throw IllegalStateException(
                "Restore stage verification failed: the staged manifest is not intact"
            )
        }

        return userFiles
    }

    /**
     * Copies an unreadable local file into [RECOVERY_DIR] byte-for-byte before
     * a merge overwrites it. A warning naming the recovery file is appended so
     * the restore summary tells the user where their old data went.
     *
     * Best-effort: if even the copy fails, the restore still proceeds (the
     * original bytes have not been overwritten at this point) and the warning
     * says the copy failed.
     */
    private suspend fun preserveCorruptLocal(
        store: FileStore,
        path: String,
        warnings: MutableList<String>
    ) {
        if (!store.exists(path)) return
        val stamp = IsoTime.now().replace(":", "-").replace(".", "-")
        val dest = "$RECOVERY_DIR/${path.substringAfterLast('/')}-corrupt-$stamp"
        try {
            store.write(dest, store.read(path))
            warnings += "Your local ${path.removePrefix("library/")} couldn't be read; " +
                "a copy was kept at ${dest.removePrefix("library/")} before the restore replaced it."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (copyError: Exception) {
            warnings += "Your local ${path.removePrefix("library/")} couldn't be read and a " +
                "recovery copy could not be written (${copyError.message}); the restore proceeded."
        }
    }

    /**
     * Rebuilds `library/index/text/<docId>.json` for restored documents that
     * have no corpus yet. Backup archives deliberately exclude the search
     * index; without this, a restored library would open fine but search would
     * silently return nothing until every book was re-imported.
     *
     * Best-effort by design: indexing failure must never fail a restore that
     * already committed. One aggregate warning is reported instead.
     */
    private suspend fun rebuildMissingSearchText(
        store: FileStore,
        docs: List<dev.folio.mobile.core.model.ManifestDoc>,
        warnings: MutableList<String>
    ) {
        var failed = 0
        for (doc in docs) {
            val searchPath = "library/index/text/${doc.id}.json"
            if (store.exists(searchPath)) continue
            val originalPath = doc.files.firstOrNull { it.path.contains("/original/") }?.path
            if (originalPath == null || !store.exists(originalPath)) {
                failed++
                continue
            }
            try {
                val original = store.read(originalPath)
                val searchText = ImportDispatch
                    .import(original, originalPath.substringAfterLast('/'))
                    .searchText
                if (searchText.isNotEmpty()) {
                    store.write(
                        searchPath,
                        backupJson.encodeToString(
                            ListSerializer(SearchChapterText.serializer()),
                            searchText
                        )
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                failed++
                android.util.Log.w(
                    "BackupRestore",
                    "Search rebuild failed for ${doc.id}: ${e.message}"
                )
            }
        }
        if (failed > 0) {
            warnings += "Search text couldn't be rebuilt for $failed restored book(s); " +
                "they open normally but won't appear in search until re-imported."
        }
    }
}
