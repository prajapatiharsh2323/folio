package dev.folio.mobile.core.importer

import dev.folio.mobile.core.clip.EpubBuilder
import dev.folio.mobile.core.model.SourceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlSanitizerTest {

    private fun sanitize(html: String) = HtmlImporter.sanitizeArticleHtml(html)

    @Test
    fun `removes script tags and their content`() {
        val out = sanitize("<p>keep</p><script>alert('xss')</script><p>more</p>")
        assertFalse(out.contains("script"))
        assertFalse(out.contains("alert"))
        assertTrue(out.contains("keep"))
        assertTrue(out.contains("more"))
    }

    @Test
    fun `removes inline event handlers`() {
        val out = sanitize("<p onclick=\"steal()\" onerror=\"x()\" onload=\"y()\">text</p>")
        assertFalse(out.contains("onclick"))
        assertFalse(out.contains("onerror"))
        assertFalse(out.contains("onload"))
        assertFalse(out.contains("steal"))
        assertTrue(out.contains("text"))
    }

    @Test
    fun `removes javascript hrefs`() {
        val out = sanitize("<p><a href=\"javascript:evil()\">click</a></p>")
        assertFalse(out.contains("javascript:"))
        assertTrue(out.contains("click"))
    }

    @Test
    fun `removes iframe object form svg math style`() {
        val out = sanitize(
            "<iframe src=\"//evil\"></iframe><object data=\"x\"></object>" +
                "<form action=\"/steal\"><input></form><svg onload=\"x()\"></svg>" +
                "<math></math><style>body{}</style><p>safe</p>"
        )
        for (tag in listOf("iframe", "object", "form", "svg", "math", "style", "input")) {
            assertFalse("must strip <$tag>: $out", out.contains("<$tag"))
        }
        assertTrue(out.contains("safe"))
    }

    @Test
    fun `keeps allowed article tags`() {
        val out = sanitize(
            "<h2>Title</h2><p>Body with <em>emphasis</em> and <strong>bold</strong>.</p>" +
                "<blockquote>quote</blockquote><pre><code>code()</code></pre>" +
                "<table><tr><td>cell</td></tr></table>" +
                "<figure><img src=\"x.jpg\" alt=\"pic\"/><figcaption>cap</figcaption></figure>" +
                "<hr/><p>sub<sup>1</sup> and sub<sub>2</sub></p>"
        )
        for (tag in listOf("h2", "p", "em", "strong", "blockquote", "pre", "code", "table", "td", "figure", "img", "figcaption", "hr", "sup", "sub")) {
            assertTrue("must keep <$tag>: $out", out.contains("<$tag"))
        }
    }

    @Test
    fun `links get rel noopener enforced`() {
        val out = sanitize("<p><a href=\"https://example.com\">link</a></p>")
        assertTrue(out.contains("rel=\"noopener noreferrer\""))
        assertTrue(out.contains("href=\"https://example.com\""))
    }

    @Test
    fun `img src limited to safe protocols`() {
        val out = sanitize("<p><img src=\"file:///etc/passwd\"/><img src=\"data:image/png;base64,iVBOR\"/></p>")
        assertFalse(out.contains("file:"))
    }
}

class HtmlImporterTest {

    @Test
    fun `extracts title author lang and content`() {
        val html = """
        <html lang="en"><head><title>My Article</title>
        <meta name="author" content="Jane Doe"/></head>
        <body><nav>menu</nav><article><p>Article body with enough words to pass the size heuristic threshold check.</p></article>
        <footer>footer junk</footer></body></html>
        """.trimIndent()
        val result = HtmlImporter.import(html.toByteArray(), "article.html")
        assertEquals("My Article", result.meta.title)
        assertEquals("Jane Doe", result.meta.author)
        assertEquals("en", result.meta.language)
        assertEquals(SourceFormat.Html, result.meta.sourceFormat)
        // article body selected, nav/footer excluded
        val clean = result.clean!!.indexHtml
        assertTrue(clean.contains("Article body"))
        assertFalse(clean.contains("footer junk"))
    }

    @Test
    fun `singlefile data-uri images extracted and rewritten`() {
        // 1x1 red PNG
        val pngBytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 1, 1, 2, 3, 4, 5, 6
        )
        val b64 = java.util.Base64.getEncoder().encodeToString(pngBytes)
        val html = "<html><head><title>Pic</title></head><body><article>" +
            "<p>Paragraph one with plenty of running text so the body heuristic is satisfied here.</p>" +
            "<img src=\"data:image/png;base64,$b64\"/></article></body></html>"
        val result = HtmlImporter.import(html.toByteArray(), "pic.html")
        val clean = result.clean!!
        assertEquals(1, clean.images.size)
        assertTrue(clean.images[0].name.endsWith(".png"))
        assertTrue("src rewritten to local images/", clean.indexHtml.contains("src=\"images/${clean.images[0].name}\""))
        assertFalse(clean.indexHtml.contains("data:image"))
        // image stored in the epub
        val epubNames = EpubFixtures.zipEntryNames(result.reading!!.bytes)
        assertTrue(epubNames.contains("OEBPS/images/${clean.images[0].name}"))
    }

    @Test
    fun `title falls back to filename and devanagari to hindi`() {
        val html = "<html><body><article><p>यह पाठ पर्याप्त लंबा है और अधिकांश अक्षर देवनागरी में लिखे गए हैं ताकि हिंदी पहचानी जा सके।</p></article></body></html>"
        val result = HtmlImporter.import(html.toByteArray(), "saved-page.html")
        assertEquals("saved-page", result.meta.title)
        assertEquals("hi", result.meta.language)
    }

    @Test
    fun `hostile page produces clean output end to end`() {
        val html = """
        <html><head><title>Trap</title></head><body>
        <article><p>Innocent paragraph packed with plenty of words so the article heuristic picks it up for sure today.</p>
        <script>fetch('evil')</script>
        <img src="x" onerror="boom()">
        <a href="javascript:bad()">bad</a></article></body></html>
        """.trimIndent()
        val result = HtmlImporter.import(html.toByteArray(), "trap.html")
        val clean = result.clean!!.indexHtml
        val epub = result.reading!!.bytes.toString(Charsets.UTF_8)
        for (forbidden in listOf("onerror", "boom", "evil", "javascript:")) {
            assertFalse("forbidden string '$forbidden' in clean html", clean.contains(forbidden))
            assertFalse("forbidden string '$forbidden' in epub bytes", epub.contains(forbidden))
        }
        assertTrue(clean.contains("Innocent paragraph"))
        assertTrue(clean.contains("rel=\"noopener noreferrer\""))
    }

    @Test
    fun `search text is plain`() {
        val html = "<html><head><title>T</title></head><body><article><p>Hello <em>inline</em> &amp; escaped</p></article></body></html>"
        val result = HtmlImporter.import(html.toByteArray())
        assertTrue(result.searchText[0].text.startsWith("Hello inline"))
        assertFalse(result.searchText[0].text.contains("<"))
    }

    @Test
    fun `image cap enforced at 40`() {
        // Build 45 DISTINCT tiny data-URI images; expect exactly 40 extracted
        val img = { n: Int ->
            val b64 = java.util.Base64.getEncoder().encodeToString("tinyimg-$n".toByteArray())
            "<img src=\"data:image/gif;base64,$b64\"/>"
        }
        val html = "<html><head><title>Many</title></head><body><article>" +
            "<p>Filler text long enough to satisfy the content heuristic for article extraction path.</p>" +
            (0..44).joinToString("") { img(it) } + "</article></body></html>"
        val result = HtmlImporter.import(html.toByteArray(), "many.html")
        assertEquals(40, result.clean!!.images.size)
        assertFalse(result.clean.indexHtml.contains("data:image/gif;base64"))
    }

    @Test
    fun `identical images are deduped to one stored file`() {
        val b64 = java.util.Base64.getEncoder().encodeToString("samebytes".toByteArray())
        val img = "<img src=\"data:image/gif;base64,$b64\"/>"
        val html = "<html><head><title>Dup</title></head><body><article>" +
            "<p>Filler text long enough to satisfy the content heuristic for article extraction here.</p>" +
            img + img + img + "</article></body></html>"
        val result = HtmlImporter.import(html.toByteArray(), "dup.html")
        assertEquals(1, result.clean!!.images.size)
        assertEquals(3, Regex("src=\"images/[0-9a-f]{16}\\.gif\"").findAll(result.clean.indexHtml).count())
    }

    @Test
    fun `xhtml builder escapes bare amps in img src`() {
        // regression guard: toXhtml must escape a bare & in src (query strings)
        val out = EpubBuilder.toXhtml("<img src=\"lots.jpg?a=1&b=2\"/>")
        assertTrue(out.contains("a=1&amp;b=2"))
        assertTrue(out.endsWith("/>"))
    }
}
