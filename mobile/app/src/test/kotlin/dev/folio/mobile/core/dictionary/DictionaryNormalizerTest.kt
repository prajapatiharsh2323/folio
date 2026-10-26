package dev.folio.mobile.core.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Selection normalization contract (D1): trim + strip surrounding
 * punctuation only, keep valid internal apostrophes/hyphens, case-fold on
 * English, NFKC, whitespace collapse, phrase cap, and honest nulls for
 * unusable selections.
 */
class DictionaryNormalizerTest {

    private fun normal(raw: String?): DictionaryQuery? = DictionaryNormalizer.normalize(raw)

    @Test
    fun `plain word survives`() {
        val q = normal("run")
        assertEquals("run", q?.displayKey)
    }

    @Test
    fun `surrounding punctuation and hyphen keeps size`() {
        assertEquals("run", normal("  (run)!   ")?.displayKey)
        assertEquals("run", normal("…run…")?.displayKey)
        assertEquals("run", normal("\u201Crun\u201D")?.displayKey)
    }

    @Test
    fun `internal apostrophes and hyphens are preserved`() {
        assertEquals("don't", normal("don't")?.displayKey)
        assertEquals("don't", normal("(“don't”)")?.displayKey)
        assertEquals("well-being", normal("well-being,")?.displayKey)
    }

    @Test
    fun `typographic apostrophes fold to the dataset's ascii keys`() {
        // EPUB text normally carries U+2019; the pack stores ASCII keys
        // (aren't → relaxed "arent"). Without the fold a real-book
        // selection of "aren’t" misses an entry the dataset has.
        assertEquals("don't", normal("don’t")?.displayKey)
        assertEquals("aren't", normal("aren’t.")?.displayKey)
        assertEquals("don't", normal("(“don’t”)")?.displayKey)
        assertEquals("arent", DictionaryNormalizer.relaxedKey(normal("aren’t")!!))
        assertEquals("don't", DictionaryNormalizer.cleanDisplay("’don’t’"))
    }

    @Test
    fun `mixed case folds to lowercase`() {
        assertEquals("president", normal("President")?.displayKey)
        assertEquals("don't", normal("DON'T")?.displayKey)
    }

    @Test
    fun `internal whitespace collapses to a single space`() {
        assertEquals("give up", normal(" give   up ")?.displayKey)
    }

    @Test
    fun `unicode normalization does not corrupt devanagari characters`() {
        assertEquals("दौड़", normal("दौड़")?.displayKey)
    }

    @Test
    fun `compatibility ligatures are normalized (NFKC)`() {
        assertEquals("fia", normal("ﬁa")?.displayKey)
    }

    @Test
    fun `blank and whitespace-only selections are rejected`() {
        assertNull(normal(null))
        assertNull(normal(""))
        assertNull(normal("   "))
    }

    @Test
    fun `punctuation-only selections are rejected`() {
        assertNull(normal("..."))
        assertNull(normal("“”"))
        assertNull(normal("!?"))
    }

    @Test
    fun `paragraph-length selections are rejected`() {
        val paragraph = "This is a long paragraph far beyond any single word and it " +
            "must not be treated as a dictionary query by any means."
        assertNull(normal(paragraph))
    }

    @Test
    fun `more than three words is rejected`() {
        assertNull(normal("one two three four"))
        assertNotNull(normal("one two three"))
    }

    @Test
    fun `relaxed key removes hyphen space and apostrophe`() {
        val q = normal("well-being")
        assertNotNull(q)
        assertEquals(
            "wellbeing",
            DictionaryNormalizer.relaxedKey(q!!),
        )
        assertEquals(
            "giveup",
            DictionaryNormalizer.relaxedKey(normal("give up")!!),
        )
    }

    @Test
    fun `clean display keeps original casing strips punctuation`() {
        assertEquals("President", DictionaryNormalizer.cleanDisplay("(President)"))
        assertEquals("don't", DictionaryNormalizer.cleanDisplay("'don't'"))
    }
}
