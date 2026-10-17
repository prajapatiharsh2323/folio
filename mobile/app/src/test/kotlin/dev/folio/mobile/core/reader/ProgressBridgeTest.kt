package dev.folio.mobile.core.reader

import dev.folio.mobile.core.model.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests only the primitive core of the bridge. Readium `Locator`/`Url` values
 * require the Android runtime (`android.net.Uri`), which plain JVM unit tests
 * do not provide — see ProgressBridge KDoc. The Locator overloads are thin
 * wrappers exercised by the instrumented reader test when it lands.
 */
class ProgressBridgeTest {

    private val cfi = "epubcfi(/6/4!/4/2,/1:2)"
    private val href = "OEBPS/chapter_1.xhtml"

    @Test
    fun `extracts the epubcfi fragment and ignores foreign fragments`() {
        assertEquals(cfi, ProgressBridge.extractedCfi(listOf("other", cfi)))
        assertNull(ProgressBridge.extractedCfi(listOf("not-a-cfi")))
        assertNull(ProgressBridge.extractedCfi(emptyList()))
    }

    @Test
    fun `location string prefers CFI and falls back to href`() {
        assertEquals(cfi, ProgressBridge.locationString(listOf(cfi), href))
        // PWA stores epub.js CFI strings; without a CFI the chapter href is used
        assertEquals(href, ProgressBridge.locationString(emptyList(), href))
    }

    @Test
    fun `percent is total publication progression defaulting to zero`() {
        assertEquals(0.4291, ProgressBridge.locationPercent(0.4291), 0.00001)
        assertEquals(0.0, ProgressBridge.locationPercent(null), 0.00001)
    }

    @Test
    fun `buildProgress maps onto schema 1 fields`() {
        val p = ProgressBridge.buildProgress(
            docId = "doc-1",
            fragments = listOf(cfi),
            href = href,
            totalProgression = 0.4291,
            updatedAt = "2026-10-17T00:00:00Z"
        )
        assertEquals("doc-1", p.docId)
        assertEquals(cfi, p.location)
        assertEquals(0.4291, p.percent, 0.00001)
        assertEquals("2026-10-17T00:00:00Z", p.updatedAt)
    }
}
