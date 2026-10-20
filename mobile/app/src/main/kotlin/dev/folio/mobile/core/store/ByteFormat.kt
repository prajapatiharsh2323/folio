package dev.folio.mobile.core.store

import java.util.Locale

/**
 * Human-readable byte sizes for the Settings storage rows. Small values stay
 * in KB/MB; large values promote to GB so the UI never shows five-digit MB.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return unit(kb, "KB")
    val mb = kb / 1024.0
    if (mb < 1024) return unit(mb, "MB")
    return unit(mb / 1024.0, "GB")
}

private fun unit(value: Double, suffix: String): String =
    String.format(Locale.ROOT, "%.1f %s", value, suffix)
