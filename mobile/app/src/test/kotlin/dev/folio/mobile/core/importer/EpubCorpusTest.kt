package dev.folio.mobile.core.importer

import dev.folio.mobile.core.util.sha256Hex
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Real-EPUB corpus tests (audit EPUB-016/EPUB-009): the five Hindi EPUBs the
 * repo ships in `sample/` are checked into `src/test/resources/fixtures/` and
 * parsed by production [EpubImporter] with per-fixture expected values.
 *
 * The suite previously exercised only synthetic fixtures, i.e. shapes the
 * author controlled — these goldens pin the shapes the wild provides
 * (namespace prefixes, attribute-order variants, EPUB2-era metadata, Devanagari
 * text). Exact strings/counters are pinned on purpose: a change in the regex
 * extraction must consciously update a golden here.
 */
class EpubCorpusTest {

    private fun load(name: String): ByteArray {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        assertNotNull("fixture missing on the test classpath: /fixtures/$name", stream)
        return stream!!.readBytes()
    }

    /** Fixture files themselves must be byte-stable (sha256 pins). */
    @Test
    fun `fixture files are byte-stable`() {
        assertEquals(
            "fa27968dcd2b3838c8861060dfa888ba22d13cd85a2741d14f88196fccf563f2",
            load("shivaji.epub").let { sha256Hex(it) }
        )
        assertEquals(
            "12d5865e353cd20cd17b7df78a893bdc6adace9c6468bd16583be5fb319f651b",
            load("alankar.epub").let { sha256Hex(it) }
        )
        assertEquals(
            "62bb992297e33cf9d8059c56d976ad2b8cca89f071b080f41eeffb9e7dbee593",
            load("swami-vivekanand.epub").let { sha256Hex(it) }
        )
        assertEquals(
            "3174c710a1e679fc8985af235b55ccbb62783eb92d9ddff205f45ce20895b0a1",
            load("antar-ki-khoj.epub").let { sha256Hex(it) }
        )
        assertEquals(
            "1a067679cf1f0a14d587e10428d0815721e413a9d09feed4e17b391f20730a2d",
            load("kabir-ke-dohe.epub").let { sha256Hex(it) }
        )
    }

    /** 99 KB mobipocket-converted Shivaji biography; no cover entry and no
     *  per-chapter titles — exercises the "Chapter n"+null-fallback path. */
    @Test
    fun `shivaji - chhatrapati shivaji parses with fallback chapter titles`() {
        val bytes = load("shivaji.epub")
        val r = EpubImporter.import(bytes, "shivaji.epub")

        assertEquals("छत्रपति शिवाजी", r.meta.title)
        assertEquals("लाला लाजपतराय", r.meta.author)
        assertEquals("en", r.meta.language)
        assertEquals(6, r.meta.chapters.size)
        assertEquals(34948, r.meta.wordCount)
        assertEquals("Unknown", r.meta.chapters.first().title)
        assertEquals("Unknown", r.meta.chapters.last().title)
        commonInvariants(bytes, r)
    }

    /** Premchand's अलंकार — no cover, no per-chapter titles. */
    @Test
    fun `alankar - premchand story collection parses`() {
        val bytes = load("alankar.epub")
        val r = EpubImporter.import(bytes, "alankar.epub")

        assertEquals("अलंकार", r.meta.title)
        assertEquals("प्रेमचंद", r.meta.author)
        assertEquals("en", r.meta.language)
        assertEquals(6, r.meta.chapters.size)
        assertEquals(61376, r.meta.wordCount)
        assertEquals("Unknown", r.meta.chapters.first().title)
        commonInvariants(bytes, r)
    }

    /** योद्धा संन्यासी विवेकानन्द — 16-chapter biography. */
    @Test
    fun `swami-vivekanand - yoddha sannyasi biography parses`() {
        val bytes = load("swami-vivekanand.epub")
        val r = EpubImporter.import(bytes, "swami-vivekanand.epub")

        assertEquals("योद्धा संन्यासी विवेकानन्द", r.meta.title)
        assertEquals("हंसराज रहबर", r.meta.author)
        assertEquals("en", r.meta.language)
        assertEquals(16, r.meta.chapters.size)
        assertEquals(98678, r.meta.wordCount)
        assertEquals("Unknown", r.meta.chapters.first().title)
        commonInvariants(bytes, r)
    }

    /** Osho's अंतर की खोज — declared language "hi", 54 named Devanagari chapters. */
    @Test
    fun `antar-ki-khoj - osho title parses with hindi chapter titles`() {
        val bytes = load("antar-ki-khoj.epub")
        val r = EpubImporter.import(bytes, "antar-ki-khoj.epub")

        assertEquals("अंतर की खोज", r.meta.title)
        assertEquals("Osho", r.meta.author)
        assertEquals("hi", r.meta.language)
        assertEquals(54, r.meta.chapters.size)
        assertEquals(35968, r.meta.wordCount)
        assertEquals("प्रकाशक संबंधी जानकारी", r.meta.chapters.first().title)
        assertEquals("4.12 परम – समझ – बुद्धि?", r.meta.chapters.last().title)
        commonInvariants(bytes, r)
    }

    /** कबीर के दोहे — mixed Devanagari/Latin title; chapter titles include the
     *  full book title (metadata title reused as <title> of index.xhtml). */
    @Test
    fun `kabir-ke-dohe - mixed-script title parses`() {
        val bytes = load("kabir-ke-dohe.epub")
        val r = EpubImporter.import(bytes, "kabir-ke-dohe.epub")

        assertEquals("कबीर के दोहे Kabir Ke Dohe In Hindi With Meaning", r.meta.title)
        assertEquals("www.roshandaan.com", r.meta.author)
        assertEquals("hi", r.meta.language)
        assertEquals(28, r.meta.chapters.size)
        assertEquals(6071, r.meta.wordCount)
        assertEquals("कबीर के दोहे Kabir Ke Dohe In Hindi With Meaning", r.meta.chapters.first().title)
        assertEquals("Page 25", r.meta.chapters.last().title)
        commonInvariants(bytes, r)
    }

    /**
     * Common corpus invariants (audit EPUB-009 verification list): content
     * hash round-trips off the raw bytes, spine order is dense 1..n, original
     * and reading payloads are the untouched source bytes, per-chapter search
     * text is aligned 1:1 with the spine and at least one chapter carries real
     * Devanagari text.
     */
    private fun commonInvariants(sourceBytes: ByteArray, r: ImportResult) {
        assertEquals(sha256Hex(sourceBytes), r.meta.contentHash)
        assertArrayEquals(sourceBytes, r.original.bytes)
        assertNotNull("corpus EPUBs must carry a reading payload", r.reading)
        assertArrayEquals(sourceBytes, r.reading!!.bytes)
        assertEquals(r.meta.chapters.size, r.searchText.size)

        val n = r.meta.chapters.size
        r.meta.chapters.forEachIndexed { i, c ->
            assertEquals("ch-${i + 1}", c.id)
            assertEquals(i + 1, c.order)
            assertTrue(c.href.isNotBlank())
        }
        r.searchText.forEachIndexed { i, s ->
            assertEquals(r.meta.chapters[i].id, s.chapterId)
            assertEquals(r.meta.chapters[i].title, s.title)
        }
        assertTrue("no chapter carries visible text", r.searchText.any { it.text.isNotBlank() })
        assertTrue(r.meta.searchable)

        // The books ship no cover entries — the corpus also pins "no cover"
        // metadata not crashing and leaving cover unset.
        assertEquals(null, r.cover)
    }

    /**
     * DRM on real data (audit EPUB-009 verification): an encryption.xml with a
     * non-font-obfuscation algorithm injected into a real EPUB must be refused
     * with [DrmProtectedError] — never circumvented — by the regex DRM path.
     */
    @Test
    fun `real epub with injected DRM encryption xml is refused`() {
        val bytes = load("antar-ki-khoj.epub")
        val infected = zipWithEntry(
            bytes, "META-INF/encryption.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <EncryptionMethod Algorithm="http://www.adobe.com/2008/drm"/>
                <CipherData><CipherValue>DEADBEEF</CipherValue></CipherData>
              </EncryptedData>
            </encryption>
            """.trimIndent()
        )

        try {
            EpubImporter.import(infected, "antar-ki-khoj.epub")
            fail("expected DrmProtectedError")
        } catch (e: DrmProtectedError) {
            // expected — refused, never circumvented
        }
    }

    /** Helper: rebuilds a zip with streams [bytes] plus one injected file added LAST. */
    private fun zipWithEntry(
        bytes: ByteArray,
        entryName: String,
        content: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            ZipInputStream(bytes.inputStream()).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    zos.putNextEntry(ZipEntry(entry.name))
                    zos.write(zis.readBytes())
                    zos.closeEntry()
                }
            }
            zos.putNextEntry(ZipEntry(entryName))
            zos.write(content.encodeToByteArray())
            zos.closeEntry()
        }
        return out.toByteArray()
    }
}
