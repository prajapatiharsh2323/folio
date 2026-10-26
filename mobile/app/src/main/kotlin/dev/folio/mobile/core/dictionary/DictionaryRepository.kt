package dev.folio.mobile.core.dictionary

/**
 * Coordinates a dictionary lookup (D1): normalize the reader's selection,
 * run the local data source, and fold outcomes into the UI-facing
 * [DictionaryUiState]. Local-first by construction — there is no online
 * provider in this build, so local results can never be shadowed by network
 * behavior, and a miss is returned honest.
 *
 * The data source is a constructor seam so JVM tests drive the repository
 * with fakes (see DictionaryRepositoryTest).
 */
class DictionaryRepository(
    private val local: OfflineDictionaryDataSource,
) {

    /**
     * Pure normalization is separated from I/O so both the selection capture
     * (ReaderActivity) and the repository share one definition of "valid".
     */
    fun normalize(raw: String?): DictionaryQuery? = DictionaryNormalizer.normalize(raw)

    /** Releases data-source resources at teardown; safe to call any time. */
    suspend fun close() = local.close()

    suspend fun lookup(rawSelection: String?): DictionaryUiState {
        val query = DictionaryNormalizer.normalize(rawSelection)
            ?: return DictionaryUiState.Invalid(
                "This selection is not a single word or short phrase.",
            )
        return when (val outcome = local.lookup(query)) {
            is DictionaryOutcome.Match -> DictionaryUiState.Ready(
                query = outcome.query,
                entries = outcome.entries,
                meta = outcome.meta,
                isExactMatch = outcome.isExactMatch,
            )
            is DictionaryOutcome.NoMatch -> DictionaryUiState.NoMatch(
                query = outcome.query,
            )
            is DictionaryOutcome.Unavailable -> DictionaryUiState.Unavailable(
                message = outcome.message,
                retryable = outcome.retryable,
            )
            is DictionaryOutcome.InvalidQuery -> DictionaryUiState.Invalid(
                reason = outcome.reason,
            )
        }
    }
}
