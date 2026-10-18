package dev.folio.mobile.core.backup

import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.MANIFEST_PATH
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.core.model.Manifest
import dev.folio.mobile.core.model.Progress
import dev.folio.mobile.core.store.FileStore
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.UUID

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
 * 2. Replace: wipes manifest/docs/user/index first, then writes backup data.
 *
 * The archive is verified before any local state is touched; unsafe/corrupt
 * archives (or a missing/corrupt manifest) throw and nothing is modified.
 */
object BackupRestore {

    private fun epochMs(iso: String?): Long =
        runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrElse { 0L }

    suspend fun restore(
        store: FileStore,
        manager: LibraryManager,
        zipBytes: ByteArray,
        mode: RestoreMode
    ): Pair<RestoreSummary, VerificationResult> {
        // 1. Verify the archive thoroughly before touching local state.
        val verification = unzipAndVerifyBackup(zipBytes)
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
        val unzipped = verification.unzipped

        // 2. Validate the backup manifest.
        val backupManifestBytes = unzipped[MANIFEST_PATH]
            ?: throw IllegalArgumentException("Missing library/manifest.json in backup archive")
        val backupManifest: Manifest = runCatching { parseManifest(backupManifestBytes.decodeToString()) }
            .getOrElse { throw IllegalArgumentException("Corrupted library/manifest.json in backup archive") }

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

        // 3. Replace mode wipes first (verified archive only).
        if (mode == RestoreMode.Replace) {
            if (store.exists(MANIFEST_PATH)) store.remove(MANIFEST_PATH)
            if (store.exists("library/docs")) store.remove("library/docs", recursive = true)
            if (store.exists("library/user")) store.remove("library/user", recursive = true)
            if (store.exists("library/index")) store.remove("library/index", recursive = true)
        }

        // 4. Load the current manifest (corrupt → clean reinit, per the PWA).
        val currentManifest: Manifest =
            if (store.exists(MANIFEST_PATH)) {
                runCatching {
                    parseManifest(store.readText(MANIFEST_PATH))
                }.getOrElse {
                    Manifest(
                        libraryId = UUID.randomUUID().toString(),
                        updatedAt = java.time.Instant.now().toString()
                    )
                }
            } else {
                Manifest(
                    libraryId = backupManifest.libraryId.ifBlank { UUID.randomUUID().toString() },
                    updatedAt = java.time.Instant.now().toString()
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

            if (mode == RestoreMode.Merge) {
                if (byHash != null) {
                    duplicatesSkipped++
                    continue
                }
                if (byId != null) {
                    if (epochMs(bDoc.updatedAt) > epochMs(byId.updatedAt)) {
                        (unzipped["library/docs/${bDoc.slug}/meta.json"])?.let {
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
            }

            // Write all files belonging to this document (meta.json included).
            for (f in bDoc.files) {
                unzipped[f.path]?.let { store.write(f.path, it) }
            }
            unzipped["library/docs/${bDoc.slug}/meta.json"]?.let {
                store.write("library/docs/${bDoc.slug}/meta.json", it)
            }

            mergedDocs.add(bDoc)
            existingByHash[bDoc.contentHash] = bDoc
            existingById[bDoc.id] = bDoc
            newDocsAdded++
        }

        // 5. Annotations: replace → overwrite; merge → newer-wins by id.
        var annotationsMerged = 0
        val annotBytes = unzipped["library/user/annotations.json"]
        if (annotBytes != null) {
            runCatching {
                val backupAnnots = parseAnnotations(annotBytes.decodeToString())
                if (mode == RestoreMode.Replace) {
                    store.write("library/user/annotations.json", annotBytes)
                    annotationsMerged = backupAnnots.size
                } else {
                    val current = manager.getAnnotations()
                    val map = current.associateBy { it.id }.toMutableMap()
                    for (b in backupAnnots) {
                        val curr = map[b.id]
                        if (curr == null || epochMs(b.updatedAt) > epochMs(curr.updatedAt)) {
                            map[b.id] = b
                            annotationsMerged++
                        }
                    }
                    store.write(
                        "library/user/annotations.json",
                        backupJson.encodeToString(ListSerializer(Annotation.serializer()), map.values.toList())
                    )
                }
            }
        }

        // 6. Progress: newer-wins per doc (replace overwrites).
        var progressUpdated = 0
        val progBytes = unzipped["library/user/progress.json"]
        if (progBytes != null) {
            runCatching {
                val backupProg = parseProgressMap(progBytes.decodeToString())
                if (mode == RestoreMode.Replace) {
                    store.write("library/user/progress.json", progBytes)
                    progressUpdated = backupProg.size
                } else {
                    val current = manager.getAllProgress().toMutableMap()
                    for ((docId, p) in backupProg) {
                        val curr = current[docId]
                        if (curr == null || epochMs(p.updatedAt) > epochMs(curr.updatedAt)) {
                            current[docId] = p
                            progressUpdated++
                        }
                    }
                    store.write(
                        "library/user/progress.json",
                        backupJson.encodeToString(
                            MapSerializer(String.serializer(), Progress.serializer()),
                            current
                        )
                    )
                }
            }
        }

        // 7. Collections: replace overwrites; merge unions docIds.
        var collectionsMerged = 0
        val collBytes = unzipped["library/user/collections.json"]
        if (collBytes != null) {
            runCatching {
                val backupColls = parseCollections(collBytes.decodeToString())
                if (mode == RestoreMode.Replace) {
                    store.write("library/user/collections.json", collBytes)
                    collectionsMerged = backupColls.size
                } else {
                    val map = manager.getCollections().associateBy { it.id }.toMutableMap()
                    for (b in backupColls) {
                        val curr = map[b.id]
                        if (curr == null) {
                            map[b.id] = b
                        } else {
                            val snapshot = map[b.id]!!.copy(
                                docIds = (curr.docIds + b.docIds).distinct()
                            )
                            map[b.id] = snapshot
                        }
                        collectionsMerged++
                    }
                    store.write(
                        "library/user/collections.json",
                        backupJson.encodeToString(ListSerializer(Collection.serializer()), map.values.toList())
                    )
                }
            }
        }

        // 8. Settings: restore only in replace mode (merge keeps local).
        unzipped["library/user/settings.json"]?.takeIf { mode == RestoreMode.Replace }?.let {
            store.write("library/user/settings.json", it)
        }

        // 9. Commit the updated manifest LAST (write-order rule).
        store.write(
            MANIFEST_PATH,
            backupJson.encodeToString(
                Manifest.serializer(),
                currentManifest.copy(
                    docs = mergedDocs,
                    updatedAt = java.time.Instant.now().toString(),
                    libraryId = currentManifest.libraryId.ifBlank { backupManifest.libraryId }
                )
            )
        )

        return Pair(
            summary.copy(
                newDocsAdded = newDocsAdded,
                duplicatesSkipped = duplicatesSkipped,
                metadataUpdated = metadataUpdated,
                annotationsMerged = annotationsMerged,
                progressUpdated = progressUpdated,
                collectionsMerged = collectionsMerged
            ),
            verification
        )
    }
}
