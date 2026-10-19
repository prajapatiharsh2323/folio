package dev.folio.mobile.core.store

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Audit EPUB-003: the storage stat must sum folio's own files, not the
 *  app partition. directoryBytes() walks the tree counting regular files. */
class DirectoryBytesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sums nested regular files`() {
        val root = tmp.newFolder("root")
        File(root, "a.txt").writeText("12345") // 5 B
        val sub = File(root, "library/docs/book").apply { mkdirs() }
        File(sub, "original.epub").writeBytes(ByteArray(1000))
        File(sub, "read.html").writeText("..........") // 10 B

        assertEquals(1015L, directoryBytes(root))
    }

    @Test
    fun `empty directory sums to zero`() {
        assertEquals(0L, directoryBytes(tmp.newFolder("empty")))
    }

    @Test
    fun `missing directory sums to zero`() {
        assertEquals(0L, directoryBytes(File(tmp.root, "does-not-exist")))
    }

    @Test
    fun `directories are not counted as files`() {
        val root = tmp.newFolder("only-dirs")
        File(root, "library/docs/somebook").apply { mkdirs() }
        File(root, "library/user").apply { mkdirs() }

        assertEquals(0L, directoryBytes(root))
    }
}
