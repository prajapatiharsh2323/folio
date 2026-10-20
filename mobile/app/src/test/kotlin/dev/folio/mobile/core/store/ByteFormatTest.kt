package dev.folio.mobile.core.store

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {

    @Test
    fun `bytes stay bytes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
    }

    @Test
    fun `kilobytes and megabytes use one decimal`() {
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("12.3 MB", formatBytes((12.3 * 1024 * 1024).toLong()))
    }

    @Test
    fun `gigabytes promote instead of showing thousands of megabytes`() {
        assertEquals("1.0 GB", formatBytes(1024L * 1024L * 1024L))
        assertEquals("2.5 GB", formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }
}
