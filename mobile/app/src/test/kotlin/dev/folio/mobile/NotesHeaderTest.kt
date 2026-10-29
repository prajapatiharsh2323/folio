package dev.folio.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * S1 Phase 4 (audit P2): the Notes header mirrors the LOAD state —
 * "Nothing saved yet" only after a successful empty load, never while
 * loading or when the saved-items file can't be read.
 */
class NotesHeaderTest {

    @Test
    fun `loading beats empty`() {
        assertEquals("Loading…", notesHeaderSubtitle(loading = true, loadError = null, count = 0))
        assertEquals("Loading…", notesHeaderSubtitle(loading = true, loadError = "boom", count = 7))
    }

    @Test
    fun `error says unavailable, never nothing-saved`() {
        assertEquals(
            "Saved items unavailable",
            notesHeaderSubtitle(loading = false, loadError = "can't read", count = 0),
        )
    }

    @Test
    fun `successful load reports the list`() {
        assertEquals("Nothing saved yet", notesHeaderSubtitle(false, null, 0))
        assertEquals("1 item", notesHeaderSubtitle(false, null, 1))
        assertEquals("42 items", notesHeaderSubtitle(false, null, 42))
    }
}
