package dev.folio.mobile.core.importer

import dev.folio.mobile.core.clip.EpubBuilder
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TxtImporterTest {

    @Test
    fun `splits paragraphs and escapes html`() {
        val text = "First paragraph\nwith two lines\n\nSecond & <para>"
        val result = TxtImporter.import(text.toByteArray(), "note.txt")
        assertEquals("note", result.meta.title)
        assertTrue(result.clean!!.indexHtml.contains("<p>First paragraph"))
        assertFalse(result.clean!!.indexHtml.contains("<p>Second & <para>"))
        assertTrue(result.clean!!.indexHtml.contains("<p>Second &amp; &lt;para&gt;</p>"))
        assertEquals(1, result.meta.chapters.size)
        assertEquals(SourceFormat.Txt, result.meta.sourceFormat)
        assertEquals(DocKind.Article, result.meta.kind)
    }

    @Test
    fun `bom stripped and english detected`() {
        val text = "\uFEFFEnglish text here.\n\nSecond para."
        val result = TxtImporter.import(text.toByteArray())
        assertEquals("en", result.meta.language)
        assertFalse(result.searchText[0].text.startsWith("\uFEFF"))
    }

    @Test
    fun `devanagari dominant sets hindi`() {
        val hindi = "यह एक हिंदी वाक्य है जो काफी लंबा है ताकि अधिकांश अक्षर देवनागरी में हों।\n\nदूसरा पैराग्राफ।"
        val result = TxtImporter.import(hindi.toByteArray())
        assertEquals("hi", result.meta.language)
        assertTrue(TxtImporter.isDevanagariDominant(hindi))
        assertFalse(TxtImporter.isDevanagariDominant("mostly english words here"))
    }

    @Test
    fun `reading edition is a valid epub3`() {
        val result = TxtImporter.import("hello\n\nworld".toByteArray(), "book.txt")
        val epub = result.reading!!.bytes
        assertTrue(EpubFixtures.isZip(epub))
        EpubFixtures.assertMimetypeFirstStored(epub)
        val names = EpubFixtures.zipEntryNames(epub)
        assertTrue("META-INF/container.xml" in names)
        assertTrue("OEBPS/content.opf" in names)
        assertTrue("OEBPS/chapter_1.xhtml" in names)
        assertTrue("OEBPS/nav.xhtml" in names)
    }

    @Test
    fun `deterministic template gives stable content hash`() {
        val text = "same text\n\ntwice"
        val a = TxtImporter.import(text.toByteArray(), "a.txt")
        val b = TxtImporter.import(text.toByteArray(), "a.txt")
        assertEquals(a.clean!!.indexHtml, b.clean!!.indexHtml)
        assertEquals(a.meta.contentHash, b.meta.contentHash)
        assertTrue(
            "clean index must carry the meta charset and lang",
            b.clean.indexHtml.contains("charset=\"utf-8\"") &&
                b.clean.indexHtml.contains("<html lang=\"en\">")
        )
        // escaping parity in XHTML chapter
        val chapter = EpubBuilder.toXhtml("<p>a & b<br>nospace</p>")
        assertTrue(chapter.contains("a &amp; b<br />"))
    }
}

private fun DocKind.Companion.ArtifactValue(): DocKind = DocKind.Article
