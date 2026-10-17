package dev.folio.mobile.core.annotation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnnotationLocatorsTest {

    private val hrefs = listOf(
        "OEBPS/Text/title.xhtml",
        "OEBPS/Text/ch1.xhtml",
        "OEBPS/Text/ch2.xhtml"
    )

    @Test
    fun `cfi location resolves through spine index`() {
        // ref = 2*(spineIndex+1): spine 1 -> /6/4
        val idx = AnnotationLocators.anchorIndex(hrefs, "epubcfi(/6/4!/4/2/16/1:0)")
        assertEquals(1, idx)
    }

    @Test
    fun `cfi out of range is rejected`() {
        assertNull(AnnotationLocators.anchorIndex(hrefs, "epubcfi(/6/8!/4/2/16/1:0)"))
        assertNull(AnnotationLocators.anchorIndex(hrefs, "epubcfi(/6/99)"))
    }

    @Test
    fun `href fallback matches exactly and with leading slash`() {
        assertEquals(2, AnnotationLocators.anchorIndex(hrefs, "OEBPS/Text/ch2.xhtml"))
        assertEquals(2, AnnotationLocators.anchorIndex(hrefs, "/OEBPS/Text/ch2.xhtml"))
    }

    @Test
    fun `missing or foreign locations resolve to null`() {
        assertNull(AnnotationLocators.anchorIndex(hrefs, null))
        assertNull(AnnotationLocators.anchorIndex(hrefs, ""))
        assertNull(AnnotationLocators.anchorIndex(hrefs, "OEBPS/unknown.xhtml"))
        assertNull(AnnotationLocators.anchorIndex(hrefs, "not-a-cfi-but-no-href-match"))
    }

    @Test
    fun `empty reading order rejects everything`() {
        assertNull(AnnotationLocators.anchorIndex(emptyList(), "epubcfi(/6/4)"))
        assertNull(AnnotationLocators.anchorIndex(emptyList(), "OEBPS/Text/ch1.xhtml"))
    }
}
