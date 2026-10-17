package dev.folio.mobile.core.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class FileDetectorTest {

    @Test
    fun `epub detected by mimetype entry not extension`() {
        assertEquals(DetectedFormat.Epub, FileDetector.detectFileFormat(EpubFixtures.simpleEpub()))
    }

    @Test
    fun `plain zip without epub mimetype is unknown`() {
        val plainZip = EpubFixtures.zipEntries(listOf("some.txt" to "hello".toByteArray()))
        // Binary zip content fails strict UTF-8 decode → unknown (TS TextDecoder fatal parity)
        assertEquals(DetectedFormat.Unknown, FileDetector.detectFileFormat(plainZip))
    }

    @Test
    fun `pdf detected by magic bytes`() {
        val pdf = "%PDF-1.7 fake content".toByteArray()
        assertEquals(DetectedFormat.Pdf, FileDetector.detectFileFormat(pdf))
    }

    @Test
    fun `html detected by markers`() {
        assertEquals(DetectedFormat.Html, FileDetector.detectFileFormat("<html><body>hi</body></html>".toByteArray()))
        assertEquals(DetectedFormat.Html, FileDetector.detectFileFormat("<!DOCTYPE html><p>x</p>".toByteArray()))
        assertEquals(DetectedFormat.Html, FileDetector.detectFileFormat("\uFEFF<html lang=\"en\">".toByteArray()))
    }

    @Test
    fun `plain text is txt`() {
        assertEquals(DetectedFormat.Txt, FileDetector.detectFileFormat("यह हिंदी पाठ है".toByteArray()))
        assertEquals(DetectedFormat.Txt, FileDetector.detectFileFormat("just some words here".toByteArray()))
    }

    @Test
    fun `binary garbage is unknown`() {
        val binary = byteArrayOf(0, 1, 2, 3, -2, -3, -4, -5, 0x7f, 0x7f, 0x7f)
        assertEquals(DetectedFormat.Unknown, FileDetector.detectFileFormat(binary))
        assertEquals(DetectedFormat.Unknown, FileDetector.detectFileFormat(ByteArray(0)))
    }
}
