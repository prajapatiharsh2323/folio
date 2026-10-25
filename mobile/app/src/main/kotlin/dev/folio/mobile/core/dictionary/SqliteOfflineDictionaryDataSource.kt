package dev.folio.mobile.core.dictionary

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Local indexed lookup over the pre-built dictionary pack (D1). Plain
 * framework [SQLiteDatabase] read on a background dispatcher, single lazily
 * opened read-only connection. Corrupt/missing data maps to
 * [DictionaryOutcome.Unavailable] instead of crashing the reader; not
 * JVM-testable (android.database) — the repository seam above it is.
 */
class SqliteOfflineDictionaryDataSource(
    private val installer: DictionaryPackInstaller,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : OfflineDictionaryDataSource {

    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null
    private var cachedMeta: DictionaryMeta? = null

    override suspend fun lookup(query: DictionaryQuery): DictionaryOutcome =
        withContext(ioDispatcher) {
            val database = open()
                ?: return@withContext DictionaryOutcome.Unavailable(
                    "Dictionary data is not available on this install.",
                    retryable = true,
                )
            try {
                val exact = rows(database, COL_NORM, query.displayKey)
                val relaxed = if (exact.isEmpty()) {
                    rows(database, COL_RELAXED, DictionaryNormalizer.relaxedKey(query))
                } else {
                    emptyList()
                }
                if (exact.isEmpty() && relaxed.isEmpty()) {
                    return@withContext DictionaryOutcome.NoMatch(query)
                }
                val m = cachedMeta ?: return@withContext DictionaryOutcome.Unavailable(
                    "Dictionary metadata is missing from the pack.",
                    retryable = false,
                )
                DictionaryOutcome.Match(
                    query = query,
                    entries = (exact + relaxed).map { row -> row.toEntry() },
                    meta = m,
                    isExactMatch = exact.isNotEmpty(),
                )
            } catch (t: Throwable) {
                DictionaryOutcome.Unavailable(
                    "The dictionary could not be read.",
                    retryable = true,
                )
            }
        }

    /** Single read-only connection, opened lazily; any failure returns null. */
    private suspend fun open(): SQLiteDatabase? = mutex.withLock {
        db ?: installer.ensureInstalled()?.let { file -> openFile(file) }
    }

    private fun openFile(file: java.io.File): SQLiteDatabase? = try {
        SQLiteDatabase.openDatabase(
            file.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        ).also { opened ->
            cachedMeta = readMeta(opened)
            db = opened
        }
    } catch (t: Throwable) {
        null
    }

    private fun readMeta(database: SQLiteDatabase): DictionaryMeta? = try {
        database.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
            val kv = mutableMapOf<String, String>()
            while (cursor.moveToNext()) {
                val k = cursor.getString(0)
                val v = cursor.getString(1)
                if (k != null && v != null) kv[k] = v
            }
            DictionaryMeta(
                schemaVersion = kv["schema_version"]?.toIntOrNull() ?: 0,
                dataset = kv["dataset"].orEmpty(),
                datasetVersion = kv["dataset_version"].orEmpty(),
                // Row-integrity digest (meta.content_sha256; legacy packs
                // carried a non-recomputable file digest under 'sha256').
                sha256 = kv["content_sha256"] ?: kv["sha256"].orEmpty(),
                attribution = kv["attribution"].orEmpty(),
                licence = kv["licence"].orEmpty(),
            )
        }
    } catch (t: Throwable) {
        null
    }

    private fun rows(
        database: SQLiteDatabase,
        column: String,
        key: String,
    ): List<DictionaryRow> = try {
        database.rawQuery(
            "SELECT headword, pos, senses_json FROM dict WHERE $column = ? ORDER BY id LIMIT ?",
            arrayOf(key, LIMIT.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        DictionaryRow(
                            headword = cursor.getString(0).orEmpty(),
                            pos = cursor.getString(1),
                            sensesJson = cursor.getString(2).orEmpty(),
                        ),
                    )
                }
            }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private companion object {
        const val COL_NORM = "headword_norm"
        const val COL_RELAXED = "headword_relaxed"
        const val LIMIT = 32
    }
}
