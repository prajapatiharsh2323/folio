package dev.folio.mobile.core.library

/**
 * Outcome of reading a persisted library file.
 *
 * Data-safety contract (audits P0-01/P0-02): a MISSING file is a legitimate
 * "does not exist yet" result; a CORRUPT file must never masquerade as empty
 * data. Typed reads return [Corrupt] so UIs can distinguish "nothing saved
 * yet" from "saved data cannot be read", and mutating reads throw
 * [LibraryFileCorruptException] instead of rebuilding the file with empty
 * content.
 */
sealed interface ReadResult<out T> {
    /** File present and decoded. */
    data class Loaded<out T>(val value: T) : ReadResult<T>

    /** File absent — a valid empty state for optional user data. */
    data object Missing : ReadResult<Nothing>

    /** File exists but cannot be decoded. The bytes are left untouched. */
    data class Corrupt(val path: String, val message: String) : ReadResult<Nothing>

    /**
     * Tolerant collapse for merge/export flows that can honestly proceed with
     * a default. Never use [orDefault] to render user-facing "is empty" UI —
     * read the sealed cases directly there.
     */
    fun orDefault(fallback: @UnsafeVariance T): T = when (this) {
        is Loaded -> value
        is Missing -> fallback
        is Corrupt -> fallback
    }
}

/**
 * Thrown when a mutating library operation meets a file that exists but cannot
 * be decoded. The file is left byte-for-byte untouched; callers must surface a
 * recovery state (repair the index, restore from a backup, or — for user-state
 * files — [LibraryManager.discardCorruptUserState]), never silently rebuild
 * the file with empty data.
 */
class LibraryFileCorruptException(
    val path: String,
    message: String,
    cause: Throwable? = null
) : IllegalStateException("Library file unreadable, write blocked: $path — $message", cause)
