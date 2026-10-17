package dev.folio.mobile.core.importer

import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubImporterTest {

    @Test
    fun `imports metadata, chapters and search text from spine order`() {
        val result = EpubImporter.import(EpubFixtures.simpleEpub(), "novel.epub")
        assertEquals("Test Novel", result.meta.title)
        assertEquals("Test Author", result.meta.author)
        assertEquals("en", result.meta.language)
        assertEquals(DocKind.Book, result.meta.kind)
        assertEquals(SourceFormat.Epub, result.meta.sourceFormat)
        assertEquals("novel.epub", result.original.name)
        assertEquals(2, result.meta.chapters.size)
        assertEquals("Chapter One", result.meta.chapters[0].title)
        assertEquals(1, result.meta.chapters[0].order)
        assertEquals("OEBPS/chapter_1.xhtml", result.meta.chapters[0].href)
        // TS parity: title tag + h1 + body all collapse into the chapter plain text
        assertEquals("Chapter One Chapter One The first chapter text.", result.searchText[0].text)
        assertEquals("Chapter Two Chapter Two The second chapter text.", result.searchText[1].text)
        assertTrue(result.meta.wordCount!! > 0)
        // reading edition = original bytes passthrough
        assertEquals(result.original.bytes.size, result.reading?.bytes?.size)
    }

    @Test
    fun `epub3 properties cover-image is extracted`() {
        val result = EpubImporter.import(EpubFixtures.epubWithCover())
        assertEquals("cover.jpg", result.cover?.name)
        assertEquals("JPEG-COVER-BYTES", result.cover?.bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun `cover extension follows the source image`() {
        val result = EpubImporter.import(
            EpubFixtures.epubWithCover(
                coverHref = "images/art.png",
                coverMediaType = "image/png",
                coverBytes = "PNG-BYTES".toByteArray()
            )
        )
        assertEquals("cover.png", result.cover?.name)
        assertEquals("PNG-BYTES", result.cover?.bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun `epub2 meta name cover is extracted`() {
        // Neither id nor href contains "cover": only the meta tag can find it.
        val result = EpubImporter.import(
            EpubFixtures.epubWithCover(
                coverHref = "pics/artwork.jpg",
                coverId = "img1",
                coverProperties = null,
                viaMetaName = true
            )
        )
        assertEquals("cover.jpg", result.cover?.name)
        assertEquals("JPEG-COVER-BYTES", result.cover?.bytes?.toString(Charsets.UTF_8))
    }

    @Test
    fun `heuristic catches id containing cover without epub3 properties`() {
        val result = EpubImporter.import(
            EpubFixtures.epubWithCover(
                coverHref = "pics/artwork.jpg",
                coverId = "cover-pic",
                coverProperties = null
            )
        )
        assertEquals("cover.jpg", result.cover?.name)
    }

    @Test
    fun `epub without cover yields null`() {
        assertNull(EpubImporter.import(EpubFixtures.simpleEpub()).cover)
    }

    @Test
    fun `drm protected epub is refused`() {
        assertThrows(DrmProtectedError::class.java) {
            EpubImporter.import(EpubFixtures.drmEpub())
        }
    }

    @Test
    fun `rights xml alone is refused`() {
        val epub = EpubFixtures.zipEntries(
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to EpubFixtures.container(),
                "META-INF/rights.xml" to "<rights/>".toByteArray(),
                "OEBPS/content.opf" to EpubFixtures.opf("T", "A", "en", 1),
                "OEBPS/chapter_1.xhtml" to EpubFixtures.chapterDoc("C", "B")
            )
        )
        assertThrows(DrmProtectedError::class.java) { EpubImporter.import(epub) }
    }

    @Test
    fun `standard font obfuscation is allowed`() {
        val epub = EpubFixtures.drmEpub(algo = "http://www.idpf.org/2008/embedding")
        val result = EpubImporter.import(epub)
        assertEquals("Locked Book", result.meta.title)
    }

    @Test
    fun `cipherdata without explicit algorithm is refused`() {
        val epub = EpubFixtures.zipEntries(
            listOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to EpubFixtures.container(),
                "META-INF/encryption.xml" to
                    "<enc xmlns=\"urn:oasis\"><CipherData/></enc>".toByteArray(),
                "OEBPS/content.opf" to EpubFixtures.opf("T", "A", "en", 1),
                "OEBPS/chapter_1.xhtml" to EpubFixtures.chapterDoc("C", "B")
            )
        )
        assertThrows(DrmProtectedError::class.java) { EpubImporter.import(epub) }
    }

    @Test
    fun `extractPlainText decodes entities and strips tags`() {
        assertEquals(
            "Tom & Jerry's \"great\" &mdash; text", // mdash left as-is: TS parity
            EpubImporter.extractPlainText("<p>Tom &amp; Jerry&#39;s &quot;great&quot; &mdash; text</p>")
        )
        assertEquals("Hindi पाठ works", EpubImporter.extractPlainText("<p>Hindi पाठ works</p>"))
        assertEquals("", EpubImporter.extractPlainText("<br/><br/>"))
        // tags are replaced by a space so words never concatenate (TS parity)
        assertEquals("a b", EpubImporter.extractPlainText("<i>a</i><i>b</i>"))
    }

    @Test
    fun `resolvePath handles dot segments`() {
        assertEquals("OEBPS/a.xhtml", EpubImporter.resolvePath("OEBPS/text/", "../a.xhtml"))
        assertEquals("a.xhtml", EpubImporter.resolvePath("", "a.xhtml"))
        assertEquals("a.xhtml", EpubImporter.resolvePath("x/", "../a.xhtml"))
    }

    @Test
    fun `invalid epub throws helpful errors`() {
        val noContainer = EpubFixtures.zipEntries(
            listOf("OEBPS/content.opf" to EpubFixtures.opf("T", "A", "en", 1))
        )
        val e = assertThrows(IllegalArgumentException::class.java) { EpubImporter.import(noContainer) }
        assertTrue(e.message!!.contains("container.xml"))
    }
}
