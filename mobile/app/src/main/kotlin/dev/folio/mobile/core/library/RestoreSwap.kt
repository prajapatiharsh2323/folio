package dev.folio.mobile.core.library

import dev.folio.mobile.core.store.FileStore

/**
 * Failure-atomic directory-swap primitives for backup restores (audit P0-03).
 *
 * Strategy: a complete new library tree is written under [STAGING_DIR] while
 * the live library is untouched. Commit then moves the live areas into
 * [ROLLBACK_DIR] and the staged areas back into place with a handful of
 * atomic renames. Any rename that fails (disk full, IO error, process death)
 * is reversed so the previous library is exactly what it was — the restore
 * never continues from a half-wiped tree the way the old "wipe first" flow
 * did.
 *
 * Process-death mid-commit is self-healing: any later call to
 * [rollbackPending] notices leftover pieces in [ROLLBACK_DIR] and reverses
 * them before other work continues, which is why [LibraryManager] recovery
 * scans and [restore] both start there.
 */
object RestoreSwap {

    /** Internal work tree for an in-flight restore (never user data). */
    const val STAGING_DIR = "library/restore-staging"

    /** Snapshot of the live tree taken during a Replace commit. */
    const val ROLLBACK_DIR = "library/restore-rollback"

    private const val LIBRARY = "library"

    /** Directory areas swapped wholesale. The manifest is always handled last. */
    private val AREAS = listOf("docs", "user", "index")
    private const val MANIFEST_ENTRY = "manifest.json"

    /** True if [store.list(parent)] sees [name] (dirs are not "files" for exists()). */
    private suspend fun hasEntry(store: FileStore, parent: String, name: String): Boolean =
        runCatching { store.list(parent).contains(name) }.getOrDefault(false)

    /** Removes any leftover work trees from an earlier restore attempt. */
    suspend fun clearWorkDirs(store: FileStore) {
        for (dir in listOf(STAGING_DIR, ROLLBACK_DIR)) {
            if (hasEntry(store, LIBRARY, dir.substringAfterLast('/'))) {
                runCatching { store.remove(dir, recursive = true) }
            }
        }
    }

    /**
     * Reverses an interrupted or failed swap: everything found under
     * [ROLLBACK_DIR] goes back to its live position, staging leftovers are
     * dropped, and the work trees are removed. Returns true when a rollback
     * actually moved something (i.e. a restore had been interrupted).
     */
    suspend fun rollbackPending(store: FileStore): Boolean {
        val touched = mutableListOf<String>()
        for (area in AREAS) {
            if (hasEntry(store, ROLLBACK_DIR, area)) touched += area
        }
        val manifestSnapshot = hasFile(store, ROLLBACK_DIR, MANIFEST_ENTRY)
        if (touched.isEmpty() && !manifestSnapshot) {
            // Nothing was ever renamed in — just clean leftovers.
            clearWorkDirs(store)
            return false
        }
        // Restore live areas in the reverse of the swap order (manifest last),
        // removing any stray installed piece first (should not normally exist).
        for (area in touched) {
            runCatching {
                if (hasEntry(store, LIBRARY, area)) store.remove("$LIBRARY/$area", recursive = true)
                store.rename("$ROLLBACK_DIR/$area", "$LIBRARY/$area")
            }.onFailure { e ->
                android.util.Log.e("RestoreSwap", "Rollback rename failed for $area", e)
                throw e
            }
        }
        if (manifestSnapshot) {
            if (store.exists("$LIBRARY/$MANIFEST_ENTRY")) {
                store.remove("$LIBRARY/$MANIFEST_ENTRY")
            }
            store.rename("$ROLLBACK_DIR/$MANIFEST_ENTRY", "$LIBRARY/$MANIFEST_ENTRY")
        }
        clearWorkDirs(store)
        return true
    }

    /**
     * Commits a fully staged tree (written under [STAGING_DIR] with live
     * library-relative subpaths). Order:
     *  1. snapshot: rename live manifest/areas into [ROLLBACK_DIR]
     *  2. install: rename staged areas (then the staged manifest) into place
     *  3. any failure → full reversal, returning the pre-restore library
     *
     * [expectedLiveManifest] is the staged manifest content that must end up
     * at the live path; the caller writes it into staging beforehand.
     */
    suspend fun commitReplace(store: FileStore) {
        val staged = mutableListOf<String>()   // staged entries being installed
        if (hasEntry(store, STAGING_DIR, "docs")) staged += "docs"
        if (hasEntry(store, STAGING_DIR, "user")) staged += "user"
        if (hasEntry(store, STAGING_DIR, "index")) staged += "index"
        val stagedManifest = hasFile(store, STAGING_DIR, MANIFEST_ENTRY)

        val installed = mutableListOf<String>() // live areas produced by this commit
        try {
            // 1. Snapshot the live tree (only what exists).
            val liveManifest = store.exists("$LIBRARY/$MANIFEST_ENTRY")
            val liveAreas = AREAS.filter { hasEntry(store, LIBRARY, it) }
            for (area in AREAS) {
                if (liveAreas.contains(area)) {
                    if (hasEntry(store, ROLLBACK_DIR, area)) {
                        store.remove("$ROLLBACK_DIR/$area", recursive = true)
                    }
                    store.rename("$LIBRARY/$area", "$ROLLBACK_DIR/$area")
                }
            }
            if (liveManifest) {
                store.rename("$LIBRARY/$MANIFEST_ENTRY", "$ROLLBACK_DIR/$MANIFEST_ENTRY")
            }

            // 2. Install the staged areas (manifest LAST — the commit point).
            for (area in AREAS) {
                if (staged.contains(area)) {
                    store.rename("$STAGING_DIR/$area", "$LIBRARY/$area")
                    installed += area
                }
            }
            if (stagedManifest) {
                if (store.exists("$LIBRARY/$MANIFEST_ENTRY")) {
                    store.remove("$LIBRARY/$MANIFEST_ENTRY")
                }
                store.rename("$STAGING_DIR/$MANIFEST_ENTRY", "$LIBRARY/$MANIFEST_ENTRY")
                installed += "manifest"
            }

            // 3. The commit invariant: a restored library always has a manifest.
            if (!store.exists("$LIBRARY/$MANIFEST_ENTRY")) {
                throw IllegalStateException("Staged tree has no manifest — refusing an empty commit")
            }
            // Success: drop the rollback snapshot and any staging leftovers.
            clearWorkDirs(store)
        } catch (e: Exception) {
            // Reverse install pieces, then hand failures of the reversal to
            // rollbackPending() which re-puts the snapshot; if even that fails
            // the state is left for the next startup recovery pass.
            runCatching {
                for (piece in installed) {
                    when (piece) {
                        "manifest" -> {
                            if (store.exists("$LIBRARY/$MANIFEST_ENTRY")) {
                                store.remove("$LIBRARY/$MANIFEST_ENTRY")
                            }
                        }
                        else -> if (hasEntry(store, LIBRARY, piece)) {
                            store.remove("$LIBRARY/$piece", recursive = true)
                        }
                    }
                }
                rollbackPending(store)
            }.onFailure { rollbackError ->
                android.util.Log.e(
                    "RestoreSwap",
                    "Restore commit failed AND rollback failed: ${rollbackError.message}",
                    rollbackError
                )
            }
            throw e
        }
    }

    private suspend fun hasFile(store: FileStore, dir: String, name: String): Boolean =
        store.exists("$dir/$name")
}
