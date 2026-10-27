package dev.folio.mobile.core.util

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Single source of truth for the ISO-8601 timestamps written into the
 * schema-1 library files (`importedAt`, `updatedAt`, `createdAt`).
 *
 * Android's system clock does not have a fixed precision: `Instant.now()`
 * prints 0, 3, 6 or 9 fractional digits depending on the device/clock. An
 * older helper in `LibraryManager` did `Instant.now().toString().take(24)`
 * and appended `"Z"`, which is only valid when the source string happened to
 * be longer than 24 characters. On a millisecond clock the source is exactly
 * `"2026-10-26T18:25:33.947Z"` (24 chars, already ending in `Z`), so the
 * result became `"2026-10-26T18:25:33.947ZZ"` — unparseable by this app
 * (`Instant.parse`) and by the PWA. That silently broke "newer wins" merges:
 * both sides compared as epoch 0.
 *
 * [format] always emits exactly milliseconds and a single `Z`, matching
 * `new Date().toISOString()` (the PWA's writer).
 */
object IsoTime {

    private val MILLIS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)
            .withZone(ZoneOffset.UTC)

    /** Current UTC time as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. */
    fun now(): String = MILLIS.format(Instant.now())

    /** Formats [instant] as `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`. */
    fun format(instant: Instant): String = MILLIS.format(instant)

    /**
     * Parses a stored timestamp, returning 0 for absent/unparseable values.
     *
     * Also accepts the historical double-`Z` form written by older Android
     * builds (`"…947ZZ"`) so libraries created before the fix still compare
     * correctly until their entries are rewritten.
     */
    fun epochMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        runCatching { return Instant.parse(iso).toEpochMilli() }
        if (iso.endsWith("ZZ")) {
            runCatching { return Instant.parse(iso.dropLast(1)).toEpochMilli() }
        }
        return 0L
    }

    /** Parses a stored timestamp, or null when absent/unparseable (legacy `ZZ` tolerated). */
    fun parseOrNull(iso: String?): Instant? {
        if (iso.isNullOrBlank()) return null
        runCatching { return Instant.parse(iso) }
        if (iso.endsWith("ZZ")) {
            runCatching { return Instant.parse(iso.dropLast(1)) }
        }
        return null
    }
}
