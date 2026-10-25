package dev.folio.mobile.core.dictionary

import kotlinx.serialization.Serializable

/**
 * Dictionary layer contracts (D1). Pure Kotlin — no Android imports — so the
 * whole folder stays unit-testable on the JVM. The SQLite pack lives in
 * [SqliteOfflineDictionaryDataSource]; the pack asset in
 * `assets/dictionary/eng-hin.db` (FreeDict eng-hin 1.6, GPL-2.0 — see the
 * `meta` table + `eng-hin-COPYING.txt`).
 */

/** Parsed `dict.senses_json`: hypotheses all come from the source, never invented. */
@Serializable
data class DictSense(
    val hindi: List<String> = emptyList(),
    val example: String? = null,
)

/** Pack metadata as stored in the DB's `meta` table. */
data class DictionaryMeta(
    val schemaVersion: Int,
    val dataset: String,
    val datasetVersion: String,
    val sha256: String,
    val attribution: String,
    val licence: String,
)

/** One POS subgroup of a dictionary match (a headword can have several). */
data class DictionaryEntry(
    val headword: String,
    val partOfSpeech: String?,
    val senses: List<DictSense>,
)

/**
 * A lookup run. [query] is the reader's raw selection; [displayKey] is the
 * normalized (trim/punctuation-stripped) form shown as the looked-up term.
 */
data class DictionaryQuery(
    val raw: String,
    val displayKey: String,
)

/** Terminal outcomes only — no bare spinners; loading lives in UI state. */
sealed interface DictionaryOutcome {
    /**
     * A local hit. [entries] keeps the source's group order (source file
     * order for each POS row). [isExactMatch] is false when the match came
     * through the relaxed hyphen/space/apostrophe key — e.g. the dataset
     * stores "well-being" as "well being" — and the UI must say so.
     */
    data class Match(
        val query: DictionaryQuery,
        val entries: List<DictionaryEntry>,
        val meta: DictionaryMeta,
        val isExactMatch: Boolean,
    ) : DictionaryOutcome

    /** Definitive local miss (explicit — never a blank sheet or fake entry). */
    data class NoMatch(val query: DictionaryQuery) : DictionaryOutcome

    /** Pack missing/corrupt/schema mismatch — recoverable via [retryable]. */
    data class Unavailable(val message: String, val retryable: Boolean) : DictionaryOutcome

    /** Query unusable (blank/punct-only/too long) — UI explains, no lookup runs. */
    data class InvalidQuery(val reason: String) : DictionaryOutcome
}

/** Reader-facing view model used by [dev.folio.mobile.reader.DictionarySheet]. */
sealed interface DictionaryUiState {
    /** Query captured immutably *before* the sheet opens (stable while UI changes). */
    object Loading : DictionaryUiState

    data class Ready(
        val query: DictionaryQuery,
        val entries: List<DictionaryEntry>,
        val meta: DictionaryMeta,
        val isExactMatch: Boolean,
    ) : DictionaryUiState

    /** Definitive local miss (explicit — never a blank sheet or fake entry). */
    data class NoMatch(val query: DictionaryQuery) : DictionaryUiState

    data class Unavailable(val message: String, val retryable: Boolean) : DictionaryUiState

    /** Selection unusable for lookup (blank / punctuation / paragraph-length). */
    data class Invalid(val reason: String) : DictionaryUiState
}
