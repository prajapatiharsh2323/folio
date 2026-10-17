package dev.folio.mobile.core.importer

import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.SourceFormat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared helper: builds tiny synthetic PDF fixtures. Raw-byte builder avoids
 *  PDType1Font static init, which requires the Android PDFBoxResourceLoader context. */
object PdfFixtures {

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")

    /** Serializes classic xref PDFs from object payloads (obj N = objects[N-1]). */
    private fun buildPdf(objects: List<String>, infoRef: Int? = null): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = mutableListOf<Int>()
        for ((i, obj) in objects.withIndex()) {
            offsets.add(out.size())
            out.write("${i + 1} 0 obj\n".toByteArray())
            out.write(obj.toByteArray(Charsets.ISO_8859_1))
            out.write("\nendobj\n".toByteArray())
        }
        val xrefPos = out.size()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray())
        out.write("0000000000 65535 f \n".toByteArray())
        for (off in offsets) out.write("%010d 00000 n \n".format(off).toByteArray())
        val trailer = if (infoRef != null) {
            "<< /Size ${objects.size + 1} /Root 1 0 R /Info $infoRef 0 R >>"
        } else {
            "<< /Size ${objects.size + 1} /Root 1 0 R >>"
        }
        out.write("trailer\n$trailer\nstartxref\n$xrefPos\n%%EOF".toByteArray())
        return out.toByteArray()
    }

    /** Page objects for `pages` pages each drawing `text` in Helvetica. */
    private fun textPdf(pages: Int, text: String, info: String?): ByteArray {
        val stream = "BT /F1 12 Tf 72 720 Td (${esc(text)}) Tj ET"
        val objects = mutableListOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [${(1..pages).joinToString(" ") { "${3 + (it - 1) * 2} 0 R" }}] /Count $pages >>"
        )
        repeat(pages) {
            val pageObj = objects.size + 1
            val contentObj = pageObj + 1
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents $contentObj 0 R /Resources << /Font << /F1 ${3 + pages * 2} 0 R >> >> >>"
            objects += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        if (info != null) objects += info
        return buildPdf(objects)
    }

    fun simplePdf(pages: Int = 2, text: String = "Hello Folio PDF text layer with plenty of characters for the searchable threshold"): ByteArray =
        textPdf(pages, text, info = null)

    fun titledPdf(title: String, author: String?): ByteArray {
        var info = "<< /Title (${esc(title)})"
        if (author != null) info += " /Author (${esc(author)})"
        info += " >>"
        return buildPdf(
            listOf(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
                info
            ),
            infoRef = 4
        )
    }

    fun lockedPdf(): ByteArray {
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            val policy = StandardProtectionPolicy("owner-pass", "user-pass", AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            val out = java.io.ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /** A PDF whose pages have no text operators at all → scanned-like fixture. */
    fun imageOnlyPdf(pages: Int = 3): ByteArray = buildPdf(
        listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [${(1..pages).joinToString(" ") { "${2 + it} 0 R" }}] /Count $pages >>"
        ) + (1..pages).map { _ ->
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>"
        }
    )
}

class PdfImporterTest {

    @Test
    fun `extracts pages as chapters and search text`() {
        val result = PdfImporter.import(PdfFixtures.simplePdf(pages = 3), "paper.pdf")
        assertEquals("paper", result.meta.title)
        assertEquals(SourceFormat.Pdf, result.meta.sourceFormat)
        assertEquals(DocKind.Book, result.meta.kind)
        assertEquals(3, result.meta.chapters.size)
        assertEquals(1, result.meta.chapters[0].order)
        assertEquals("#page=1", result.meta.chapters[0].href)
        assertEquals(3, result.searchText.size)
        assertTrue("extracted: ${result.searchText[0].text}", result.searchText[0].text.contains("Hello Folio PDF"))
        assertTrue("text layer detected", result.meta.searchable!!)
        assertNull("no reading edition for pdf", result.reading)
        assertNull("no clean edition for pdf", result.clean)
    }

    @Test
    fun `uses info dictionary title and author when present`() {
        val result = PdfImporter.import(PdfFixtures.titledPdf("Report Title", "Ann Author"), "file.pdf")
        assertEquals("Report Title", result.meta.title)
        assertEquals("Ann Author", result.meta.author)
    }

    @Test
    fun `falls back to filename title without author`() {
        val result = PdfImporter.import(PdfFixtures.titledPdf("   ", null), "named.pdf")
        assertEquals("named", result.meta.title)
        assertNull(result.meta.author)
    }

    @Test
    fun `locked pdf reports friendly message`() {
        val e = assertThrows(PdfImporter.PdfLockedException::class.java) {
            PdfImporter.import(PdfFixtures.lockedPdf(), "secret.pdf")
        }
        assertEquals("This PDF is locked", e.message)
    }

    @Test
    fun `image only pdf is not searchable`() {
        val result = PdfImporter.import(PdfFixtures.imageOnlyPdf(pages = 3), "scan.pdf")
        assertFalse("avg 0 chars/page → searchable false", result.meta.searchable!!)
        assertEquals(3, result.meta.chapters.size)
        assertEquals("", result.searchText[0].text.trim())
    }

    @Test
    fun `content hash is sha256 of original bytes`() {
        val bytes = PdfFixtures.simplePdf()
        assertEquals(dev.folio.mobile.core.util.sha256Hex(bytes), PdfImporter.import(bytes).meta.contentHash)
    }
}
