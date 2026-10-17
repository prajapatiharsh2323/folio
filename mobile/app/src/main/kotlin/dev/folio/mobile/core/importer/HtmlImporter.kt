package dev.folio.mobile.core.importer

import dev.folio.mobile.core.clip.ArticlePayload
import dev.folio.mobile.core.clip.EpubBuilder
import dev.folio.mobile.core.model.Chapter
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.util.sha256Hex
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import java.util.Base64

/**
 * Port of packages/core/src/import/html.ts (single-file path).
 *
 * DECISION (docs/decisions.md cross-ref): the PWA uses @mozilla/readability for
 * article extraction. Native uses a structural heuristic (<article> → <main> →
 * [itemprop=articleBody] → [role=main] → body) — adequate for saved/SingleFile
 * pages; full extraction scoring is revisited with the future on-device clipper.
 */
object HtmlImporter {

    /** jsoup allow-list port of the DOMPurify article allow-list (spec §6). */
    internal fun articleSafelist(): Safelist = Safelist()
        .addTags(
            "h1", "h2", "h3", "h4", "h5", "h6", "p", "ul", "ol", "li", "blockquote",
            "pre", "code", "table", "thead", "tbody", "tr", "td", "th", "caption",
            "figure", "figcaption", "img", "a", "em", "strong", "b", "i", "sup",
            "sub", "hr", "br", "span", "abbr", "cite", "q", "mark", "del", "ins", "u"
        )
        .addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "width", "height", "loading")
        // explicit protocol allow-lists — without them jsoup permits any scheme,
        // including file: (spec §6: URLs restricted to safe protocols)
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https", "data")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan", "scope")
        .addAttributes("abbr", "title")
        .addAttributes("cite", "title")
        .addEnforcedAttribute("a", "rel", "noopener noreferrer")
        .addEnforcedAttribute("a", "target", "_blank")
        .preserveRelativeLinks(true)

    /** Sanitizes any HTML down to the article allow-list. Spec rule 5: mandatory. */
    fun sanitizeArticleHtml(html: String): String {
        val cleaned = Jsoup.clean(html, articleSafelist())
        // jsoup clean() strips script/style/iframe/object/form/svg/math and every
        // on* handler by denying those tags/attributes.
        val doc = Jsoup.parseBodyFragment(cleaned)
        doc.outputSettings().prettyPrint(false)
        doc.select("a[href^=javascript:]").forEach { el ->
            el.removeAttr("href")
        }
        return doc.body().html()
    }

    /** Picks the article content using structural heuristics (see class doc). */
    internal fun extractArticleBody(doc: Document): String {
        val selectors = listOf(
            "article", "main", "[itemprop=articleBody]", "[role=main]", "div#content"
        )
        for (selector in selectors) {
            val el = doc.body().selectFirst(selector)
            // structural presence is the signal — no length gate: saved pages are
            // often short articles, and a length gate would fall through to <body>
            // and drag nav/footer chrome into the reading edition
            if (el != null && el.text().isNotBlank()) {
                return el.html()
            }
        }
        // last resort: whole body
        return doc.body().html()
    }

    /** True when the src attribute is an inline base64 image (SingleFile style). */
    private val dataUriRegex = Regex(
        "src=[\"']data:(image/[a-zA-Z0-9.+-]+);base64,([A-Za-z0-9+/=]+)[\"']"
    )

    /**
     * Imports an HTML or SingleFile document (spec §6).
     */
    fun import(bytes: ByteArray, filename: String = "article.html"): ImportResult {
        val rawHtml = bytes.toString(Charsets.UTF_8)
        val doc = Jsoup.parse(rawHtml)

        val title = doc.title().trim().ifBlank { null }
            ?: Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
                .find(rawHtml)?.groupValues?.get(1)?.trim()
        var author = Regex(
            "<meta[^>]*name=[\"']author[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE
        ).find(rawHtml)?.groupValues?.get(1)?.trim()
        var lang = Regex(
            "<html[^>]*lang=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE
        ).find(rawHtml)?.groupValues?.get(1)?.trim()

        val contentHtml = extractArticleBody(doc)

        if (lang == null && TxtImporter.isDevanagariDominant(rawHtml)) lang = "hi"
        val effectiveTitle = title ?: filename.replace(Regex("\\.html?$", RegexOption.IGNORE_CASE), "")

        // Sanitize (mandatory, spec rule 5) then extract up to 40 inline images.
        // Re-scan from 0 each pass: replaced srcs ("images/<hex>.<ext>") no longer
        // match the data-URI pattern, so there is no rescanning or loop hazard.
        var sanitized = sanitizeArticleHtml(contentHtml)
        val images = mutableListOf<ImportImage>()
        val seenNames = HashSet<String>()
        while (images.size < 40) { // spec §6: image cap
            val match = dataUriRegex.find(sanitized) ?: break
            val mime = match.groupValues[1]
            val base64 = match.groupValues[2]
            val decoded = runCatching { Base64.getMimeDecoder().decode(base64) }.getOrNull()
            if (decoded == null || decoded.isEmpty()) {
                // undecodable payload: blank it so it can never match again
                sanitized = sanitized.substring(0, match.range.first) +
                    "src=\"\"" + sanitized.substring(match.range.last + 1)
                continue
            }
            val ext = when {
                mime.contains("png", ignoreCase = true) -> "png"
                mime.contains("webp", ignoreCase = true) -> "webp"
                mime.contains("gif", ignoreCase = true) -> "gif"
                else -> "jpg"
            }
            val name = sha256Hex(decoded).take(16) + "." + ext
            val replacement = "src=\"images/$name\""
            sanitized = sanitized.substring(0, match.range.first) + replacement +
                sanitized.substring(match.range.last + 1)
            // identical images (same content hash) map to one file: rewrite the src
            // but only store the bytes once
            if (seenNames.add(name)) images += ImportImage(name, decoded)
        }
        // images beyond the cap are dropped: blank their srcs so no raw data URI
        // survives into the clean edition or the EPUB bytes
        sanitized = dataUriRegex.replace(sanitized, "src=\"\"")

        val processed = EpubBuilder.processArticle(
            ArticlePayload(
                url = filename,
                title = effectiveTitle,
                byline = author,
                lang = lang ?: "en",
                contentHtml = sanitized,
                images = images
            )
        )

        val plainText = sanitized.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ").trim()

        return ImportResult(
            meta = DocMeta(
                id = "pending",
                slug = "pending",
                title = effectiveTitle,
                author = author,
                kind = DocKind.Article,
                language = lang ?: "en",
                sourceFormat = SourceFormat.Html,
                capturedAt = java.time.Instant.now().toString(),
                importedAt = "pending",
                contentHash = sha256Hex(bytes),
                searchable = true,
                tags = emptyList(),
                collectionIds = emptyList(),
                chapters = listOf(Chapter("ch-1", effectiveTitle, order = 1, href = "chapter_1.xhtml"))
            ),
            original = ImportOriginal(filename, bytes),
            clean = ImportClean(indexHtml = processed.cleanIndexHtml, images = images),
            reading = ImportReading("book.epub", processed.readingEpub),
            searchText = listOf(SearchChapterText("ch-1", effectiveTitle, plainText))
        )
    }
}
