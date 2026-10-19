package dev.folio.mobile.core.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Date
import java.util.Locale

/**
 * Port of packages/core/src/util/hash.ts. Behavior must match the PWA exactly:
 * the same file bytes must produce the same contentHash on both platforms.
 */
fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
        String.format("%02x", it)
    }

/**
 * Slug per spec §4: `YYYY-MM-DD-title-words-xxxx` — lowercase ASCII (diacritics
 * stripped, non-alphanumerics collapsed to dashes, fallback "doc"), at most 60
 * chars, ending with 4 random hex chars. Hindi titles fall back to "doc".
 *
 * Audit EPUB-014: computes the date fields through
 * [java.time.ZonedDateTime] instead of the deprecated `Date.year/month/date`
 * getters. Both resolve through the system-default zone (these getters *are*
 * a wrap of Calendar.getInstance()) so the output string is byte-stable with
 * the PWA slug contract — DateSlugEquivalenceTest pins the equivalence, do not
 * switch the zone or the semantics without a cross-repo byte-check.
 */
fun createDocSlug(title: String, date: Date = Date()): String {
    val zoned = date.toInstant().atZone(java.time.ZoneId.systemDefault())
    val datePrefix = String.format(
        Locale.ROOT, "%04d-%02d-%02d",
        zoned.year, zoned.monthValue, zoned.dayOfMonth
    )

    var cleanTitle = Normalizer.normalize(title.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "") // strip latin diacritics
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    if (cleanTitle.isEmpty()) cleanTitle = "doc"

    val randomHex = String.format("%04x", SecureRandom().nextInt(0x10000))

    // datePrefix (10) + dash + 4 hex (4) + dash = 16 chars overhead
    val maxTitleLen = 60 - 16
    if (cleanTitle.length > maxTitleLen) {
        cleanTitle = cleanTitle.substring(0, maxTitleLen).trimEnd('-')
    }

    return "$datePrefix-$cleanTitle-$randomHex"
}
