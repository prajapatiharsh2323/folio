package dev.folio.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Audit EPUB-001: the import picker must advertise exactly what the
 * pipeline accepts (EPUB/HTML/TXT). Advertising `application/octet-stream`
 * made selecting an EPUB mislabeled by sloppy file managers work, but made
 * every PDF/`.mobi` tap a guaranteed "Unsupported format" dead-end, so it
 * was removed from the picker and the ACTION_VIEW manifest filter.
 */
class ImportMimeContractTest {

    @Test
    fun `picker mimes are exactly the formats ImportDispatch accepts`() {
        val advertised = MainActivity.IMPORT_MIMES.toList()
        assertFalse(
            "octet-stream must not be advertised (dead-end UX)",
            advertised.contains("application/octet-stream")
        )
        assertEquals(
            listOf("application/epub+zip", "text/html", "text/plain"),
            advertised
        )
    }
}
