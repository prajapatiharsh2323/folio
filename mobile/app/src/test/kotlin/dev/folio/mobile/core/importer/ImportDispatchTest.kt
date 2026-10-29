package dev.folio.mobile.core.importer

import dev.folio.mobile.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDispatchTest {

    @Test
    fun `plain text routes to txt importer`() {
        val result = ImportDispatch.import("chapter one\npara two".toByteArray(), "book.txt")
        assertEquals(SourceFormat.Txt, result.meta.sourceFormat)
    }

    @Test
    fun `html routes by content markers, not by extension`() {
        val html = "<html><head><title>T</title></head><body><article><h1>Hi</h1><p>Body</p></article></body></html>"
            .toByteArray()
        val result = ImportDispatch.import(html, "note.txt") // lying extension on purpose
        assertEquals(SourceFormat.Html, result.meta.sourceFormat)
    }

    @Test
    fun `pdf signature is unsupported (decision 2, honest rejection)`() {
        // Binary PDF bytes never become a "text article": UTF-8-hostile bytes
        // are Unknown → unsupported, and the magic-byte signature path keeps
        // a PDF detected-but-rejected rather than misdetected as plain text.
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2d, 1, 2, 0xFF.toByte(), 0xFE.toByte())
        val ex = assertThrows(UnsupportedFormatException::class.java) {
            ImportDispatch.import(pdf, "doc.pdf")
        }
        assertEquals("doc.pdf", ex.fileName)
    }

    @Test
    fun `unknown bytes throw with the file name`() {
        val ex = assertThrows(UnsupportedFormatException::class.java) {
            ImportDispatch.import(ByteArray(0), "weird.bin")
        }
        assertEquals("weird.bin", ex.fileName)
        assertTrue(ex.message!!.contains("weird.bin"))
    }
}
