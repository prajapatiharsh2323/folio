package dev.folio.mobile.core.backup

import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The filename contract is shared with the PWA (packages/core export-zip.ts):
 * `folio-backup-YYYYMMDD-HHMM.zip`, minute-granular local time.
 */
class BackupFilenameTest {

    private val previousTimeZone = TimeZone.getDefault()
    private val previousLocale = Locale.getDefault()

    @Before
    fun pinLocalFormatting() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Locale.setDefault(Locale.ROOT)
    }

    @After
    fun restoreLocale() {
        TimeZone.setDefault(previousTimeZone)
        Locale.setDefault(previousLocale)
    }

    @Test
    fun `matches the pwa naming contract`() {
        // 1970-01-01T00:00 in the pinned UTC time zone.
        assertEquals("folio-backup-19700101-0000.zip", generateBackupFilename(Date(0L)))
    }

    @Test
    fun `a serial-suffixed collision name stays a valid zip name`() {
        val base = generateBackupFilename(Date(0L))
        val collision = base.removeSuffix(".zip") + "-1.zip"
        assertEquals("folio-backup-19700101-0000-1.zip", collision)
        assertFalse(collision.endsWith(".zip.zip"))
    }
}
