package dev.folio.mobile.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pack installer contract (D1): first-use copy → digest verify → atomic
 * promote; reuse without re-copying; honest nulls for a missing asset or a
 * digest mismatch (the reader stays usable, nothing partially written).
 */
class DictionaryPackInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val payload = "FAKE-DICT-PACK-CONTENT".toByteArray(Charsets.UTF_8)

    /** sha256 of [payload], as `tools/build_dict.py` would publish it. */
    private val payloadSha: String = DictionaryPackInstaller.sha256(
        ByteArrayInputStream(payload),
    )

    private class CountingSource(
        private val streamFn: () -> InputStream?,
        private val expected: String?,
    ) : DictionaryPackSource {
        val opens = AtomicInteger(0)

        override fun open(): InputStream? = try {
            opens.incrementAndGet()
            streamFn()
        } finally {
            // open() contract: may return null when the asset is missing.
        }

        override fun expectedSha256(): String? = expected
    }

    private fun dir(): File = File(tmp.root, "pack").apply { mkdirs() }

    @Test
    fun `first install copies and verifies then promotes atomically`() = runBlocking {
        val dir = dir()
        val source = CountingSource(
            streamFn = { ByteArrayInputStream(payload) },
            expected = payloadSha,
        )
        val installer = DictionaryPackInstaller(source, { dir })
        val file = installer.ensureInstalled()
        assertNotNull(file)
        assertTrue(file!!.exists())
        assertEquals("FAKE-DICT-PACK-CONTENT", file.readText())
        assertEquals(1, source.opens.get())
        // Marker written so later launches skip hashing the whole pack.
        assertEquals(
            payloadSha,
            File(dir, "eng-hin.db.sha256").readText().trim(),
        )
    }

    @Test
    fun `matching pack is reused without re-copying`() = runBlocking {
        val dir = dir()
        val source = CountingSource(
            streamFn = { ByteArrayInputStream(payload) },
            expected = payloadSha,
        )
        val installer = DictionaryPackInstaller(source, { dir })
        assertNotNull(installer.ensureInstalled())
        val opensBefore = source.opens.get()
        assertNotNull(installer.ensureInstalled())
        assertEquals(opensBefore, source.opens.get())
    }

    @Test
    fun `digest mismatch leaves nothing promoted`() = runBlocking {
        val dir = dir()
        val source = CountingSource(
            streamFn = { ByteArrayInputStream(payload) },
            expected = "0".repeat(64),
        )
        val file = DictionaryPackInstaller(source, { dir }).ensureInstalled()
        assertNull(file)
        // No partial state: neither the target nor a .part remainder survives.
        assertTrue(!File(dir, "eng-hin.db").exists())
        assertTrue(
            dir.listFiles().orEmpty().none {
                it.name.startsWith("eng-hin.db.part")
            },
        )
    }

    @Test
    fun `missing asset is an honest null`() = runBlocking {
        val dir = dir()
        val source = CountingSource(streamFn = { null }, expected = payloadSha)
        assertNull(DictionaryPackInstaller(source, { dir }).ensureInstalled())
    }

    @Test
    fun `corrupt leftover file is replaced by a fresh verified copy`() = runBlocking {
        val dir = dir()
        val already = File(dir, "eng-hin.db")
        already.writeText("CORRUPT-DATA")
        File(dir, "eng-hin.db.sha256").writeText("deadbeef")
        val source = CountingSource(
            streamFn = { ByteArrayInputStream(payload) },
            expected = payloadSha,
        )
        val file = DictionaryPackInstaller(source, { dir }).ensureInstalled()
        assertNotNull(file)
        assertEquals("FAKE-DICT-PACK-CONTENT", file!!.readText())
    }
}
