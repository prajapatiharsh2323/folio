package dev.folio.mobile.core.importer

import dev.folio.mobile.core.clip.EpubBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared helper: builds synthetic EPUB fixtures in-test (no copyrighted files committed). */
object EpubFixtures {

    fun zipEntries(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val stored = name == "mimetype"
                val e = ZipEntry(name)
                if (stored) {
                    e.method = ZipEntry.STORED
                    e.size = bytes.size.toLong()
                    e.compressedSize = bytes.size.toLong()
                    val crc = CRC32().apply { update(bytes) }
                    e.crc = crc.value
                }
                zip.putNextEntry(e)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun chapterDoc(title: String, body: String): ByteArray =
        """<?xml version="1.0" encoding="utf-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head><title>$title</title></head>
<body><h1>$title</h1><p>$body</p></body>
</html>""".toByteArray(Charsets.UTF_8)

    fun opf(title: String, author: String, lang: String, chapterCount: Int): ByteArray {
        val items = (1..chapterCount).joinToString("\n") { i ->
            "<item id=\"ch$i\" href=\"chapter_$i.xhtml\" media-type=\"application/xhtml+xml\"/>"
        }
        val spine = (1..chapterCount).joinToString("\n") { i ->
            "<itemref idref=\"ch$i\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="id">urn:uuid:test</dc:identifier>
    <dc:title>$title</dc:title>
    <dc:creator>$author</dc:creator>
    <dc:language>$lang</dc:language>
  </metadata>
  <manifest>$items</manifest>
  <spine>$spine</spine>
</package>""".toByteArray(Charsets.UTF_8)
    }

    fun container(opfPath: String = "OEBPS/content.opf"): ByteArray =
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".toByteArray(Charsets.UTF_8)

    /** A two-chapter synthetic English EPUB. */
    fun simpleEpub(): ByteArray {
        val entries = mutableListOf<Pair<String, ByteArray>>(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container(),
            "OEBPS/content.opf" to opf("Test Novel", "Test Author", "en", 2),
            "OEBPS/chapter_1.xhtml" to chapterDoc("Chapter One", "The first chapter text."),
            "OEBPS/chapter_2.xhtml" to chapterDoc("Chapter Two", "The second chapter text.")
        )
        return zipEntries(entries)
    }

    /** EPUB with synthetic DRM encryption.xml (only for the refusal test). */
    fun drmEpub(algo: String = "http://example.com/unknown-algo"): ByteArray = zipEntries(
        listOf(
            "mimetype" to "application/epub+zip".toByteArray(),
            "META-INF/container.xml" to container(),
            "META-INF/encryption.xml" to
                """<?xml version="1.0"?>
<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
  <enc:EncryptionMethod Algorithm="$algo"/>
  <enc:CipherData><enc:CipherReference URI="OEBPS/chapter_1.xhtml"/></enc:CipherData>
</encryption>""".toByteArray(Charsets.UTF_8),
            "OEBPS/content.opf" to opf("Locked Book", "Author", "en", 1),
            "OEBPS/chapter_1.xhtml" to chapterDoc("Locked", "Encrypted")
        )
    )

    /** Builds a real article EPUB through our builder (round-trip fixture). */
    fun articleEpub(title: String = "Test Article"): ByteArray =
        EpubBuilder.buildArticleEpub(
            lang = "en",
            title = title,
            author = "Byline",
            url = "https://example.com/a",
            siteName = "Example",
            contentHtml = "<p>Hello <em>world</em></p>",
            images = emptyList(),
            publishedAt = null,
            contentHash = "0".repeat(64)
        )

    fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()

    fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        java.io.ByteArrayInputStream(bytes).use { input ->
            java.util.zip.ZipInputStream(input, Charsets.UTF_8).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    names += e.name
                    e = zip.nextEntry
                }
            }
        }
        return names
    }

    fun assertMimetypeFirstStored(epub: ByteArray) {
        val names = zipEntryNames(epub)
        assertEquals("mimetype", names.first())
        // Stored (not deflated): reconstruct raw first entry header check via
        // reading entry compression method
        java.io.ByteArrayInputStream(epub).use { input ->
            java.util.zip.ZipInputStream(input, Charsets.UTF_8).use { zip ->
                val first = zip.nextEntry!!
                assertTrue("mimetype must be stored uncompressed", first.method == java.util.zip.ZipEntry.STORED)
                assertEquals("application/epub+zip", zip.readBytes().toString(Charsets.UTF_8).trim())
            }
        }
    }
}
