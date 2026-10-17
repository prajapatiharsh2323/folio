package dev.folio.mobile.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports packages/core/tests/search.test.ts normalise/stemHindi cases. */
class SearchNormaliseTest {

    @Test
    fun `zwj and zwnj removed so باwithout and with match`() {
        val without = "नमस्ते"
        val withZwj = "नमस्\u200Dते"
        assertEquals(SearchNormalise.normalise(without), SearchNormalise.normalise(withZwj))

        val withZwnj = "नमस्\u200Cते"
        assertEquals(SearchNormalise.normalise(without), SearchNormalise.normalise(withZwnj))
    }

    @Test
    fun `nukta precomposed and decomposed forms converge under NFC`() {
        val precomposed = "फ़िल्म"
        val decomposed = java.text.Normalizer.normalize(precomposed, java.text.Normalizer.Form.NFD)
        assertEquals(SearchNormalise.normalise(precomposed), SearchNormalise.normalise(decomposed))
    }

    @Test
    fun `latin accents fold without touching devanagari marks`() {
        assertEquals("cafe", SearchNormalise.normalise("café"))
        // Devanagari vowel sign must survive: की stays की
        assertEquals("की", SearchNormalise.normalise("की"))
    }

    @Test
    fun `whitespace collapses`() {
        assertEquals("a b c", SearchNormalise.normalise("a \t\n b   c"))
    }

    @Test
    fun `stemHindi inflected suffixes per decisions`() {
        assertEquals("विद्यार्थी", SearchNormalise.stemHindi("विद्यार्थियों"))
        assertEquals("पुस्तक", SearchNormalise.stemHindi("पुस्तकों"))
        assertEquals("पुस्तक", SearchNormalise.stemHindi("पुस्तकें"))
        assertEquals("लड़की", SearchNormalise.stemHindi("लड़कियों"))
    }

    @Test
    fun `tokenise emits base token plus hindi stem`() {
        val tokens = SearchNormalise.tokenise("पुस्तकों")
        assertEquals(listOf("पुस्तकों", "पुस्तक"), tokens)
        assertEquals(listOf("plain"), SearchNormalise.tokenise("plain"))
        assertTrue(SearchNormalise.tokenise("!!!").isEmpty())
    }

    @Test
    fun `tokenise keeps devanagari marks inside words`() {
        // ि vowel sign and halant are \p{M} — never split points
        val tokens = SearchNormalise.tokenise("पुस्तकें, यात्रा. की")
        assertEquals(listOf("पुस्तकें", "पुस्तक", "यात्रा", "की"), tokens)
    }

    @Test
    fun `empty input`() {
        assertEquals("", SearchNormalise.normalise(""))
        assertEquals("", SearchNormalise.normalise("  \t "))
        assertFalse(SearchNormalise.tokenise("   ").isNotEmpty())
    }
}
