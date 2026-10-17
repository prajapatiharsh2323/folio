package dev.folio.mobile.core.clip

import dev.folio.mobile.core.importer.ImportImage
import dev.folio.mobile.core.util.sha256Hex
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Port of packages/core/src/clip/build-epub.ts (builder portions).
 * Sanitization itself is DOMPurify in the PWA — the jsoup port lands with the
 * HTML importer; callers pass already-sanitized HTML.
 */
data class ArticlePayload(
    val url: String,
    val title: String,
    val byline: String? = null,
    val siteName: String? = null,
    val lang: String = "en",
    val publishedAt: String? = null,
    /** already-sanitized HTML body (no <script>, no event handlers) */
    val contentHtml: String,
    val images: List<ImportImage> = emptyList()
)

data class ProcessedArticle(
    val cleanIndexHtml: String,
    val contentHash: String,
    val images: List<ImportImage>,
    val readingEpub: ByteArray
)

object EpubBuilder {

    fun escapeXhtml(str: String): String = str
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    /**
     * XHTML void-tag self-closing + XML-safe entities (toXhtml port).
     */
    fun toXhtml(html: String): String {
        val safeAmpHtml = html.replace(
            Regex("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[a-fA-F\\d]+);)", RegexOption.IGNORE_CASE),
            "&amp;"
        )
        return safeAmpHtml
            .replace("&nbsp;", "&#160;")
            .replace("&copy;", "&#169;")
            .replace("&reg;", "&#174;")
            .replace("&trade;", "&#8482;")
            .replace("&mdash;", "&#8212;")
            .replace("&ndash;", "&#8211;")
            .replace("&hellip;", "&#8230;")
            .replace("&ldquo;", "&#8220;")
            .replace("&rdquo;", "&#8221;")
            .replace("&lsquo;", "&#8216;")
            .replace("&rsquo;", "&#8217;")
            .replace("&laquo;", "&#171;")
            .replace("&raquo;", "&#187;")
            .replace("&bull;", "&#8226;")
            .replace("&middot;", "&#183;")
            .replace(Regex("<img([^>]*)(?<!/)>", RegexOption.IGNORE_CASE), "<img$1 />")
            .replace(Regex("<br([^>]*)(?<!/)>", RegexOption.IGNORE_CASE), "<br$1 />")
            .replace(Regex("<hr([^>]*)(?<!/)>", RegexOption.IGNORE_CASE), "<hr$1 />")
            .replace(Regex("<input([^>]*)(?<!/)>", RegexOption.IGNORE_CASE), "<input$1 />")
            .replace(Regex("<wbr([^>]*)(?<!/)>", RegexOption.IGNORE_CASE), "<wbr$1 />")
    }

    /**
     * Processes a clip payload into the clean/index.html template + EPUB 3 archive.
     * Template must stay deterministic (no volatile timestamps) so duplicate
     * content hashes match across platforms — parity with the PWA.
     */
    fun processArticle(payload: ArticlePayload): ProcessedArticle {
        val lang = payload.lang.ifBlank { "en" }
        val title = payload.title.ifBlank { "Untitled Article" }
        val author = payload.byline
        val siteName = payload.siteName
        val articleDate = payload.publishedAt ?: ""

        // 1. Rewrite images in sanitized content to local hashed names
        val namedImages = payload.images.map { img ->
            val finalPattern = Regex("^[0-9a-f]{16}\\.(jpg|png|webp|gif)$")
            if (finalPattern.matches(img.name)) {
                img // caller already finalized the name (HTML data-URI extraction)
            } else {
                val hash = sha256Hex(img.bytes)
                val prefix = hash.substring(0, 16)
                val ext = when {
                    img.name.endsWith(".png") -> "png"
                    img.name.endsWith(".webp") -> "webp"
                    img.name.endsWith(".gif") -> "gif"
                    else -> "jpg"
                }
                ImportImage("$prefix.$ext", img.bytes)
            }
        }

        val sanitized = payload.contentHtml
        // (image src rewriting by original URL requires URL-keyed payloads —
        // the clipper pipeline handles it; file importers pass empty images)

        // 2. Wrap in the minimal clean template (charset, lang, title, byline, source)
        val cleanIndexHtml = buildString {
            append("<!DOCTYPE html>\n")
            append("<html lang=\"${escapeXhtml(lang)}\">\n")
            append("<head>\n")
            append("  <meta charset=\"utf-8\" />\n")
            append("  <title>${escapeXhtml(title)}</title>\n")
            append("  <meta name=\"author\" content=\"${escapeXhtml(author ?: "")}\" />\n")
            if (articleDate.isNotEmpty()) {
                append("  <meta name=\"date\" content=\"${escapeXhtml(articleDate)}\" />\n")
            }
            append(
                "  <style>\n" +
                    "    body { font-family: serif; line-height: 1.6; max-width: 680px; margin: 2rem auto; padding: 0 1rem; color: #1C1B19; background: #FAFAF7; }\n" +
                    "    h1 { font-size: 1.8rem; margin-bottom: 0.25rem; color: #2F6F5E; }\n" +
                    "    .meta { font-size: 0.85rem; color: #6B6860; margin-bottom: 2rem; border-bottom: 1px solid #E6E3DC; padding-bottom: 1rem; }\n" +
                    "    img { max-width: 100%; height: auto; border-radius: 8px; }\n" +
                    "    blockquote { border-left: 3px solid #2F6F5E; margin: 1.5rem 0; padding-left: 1rem; font-style: italic; color: #6B6860; }\n" +
                    "  </style>\n"
            )
            append("</head>\n")
            append("<body>\n")
            append("  <h1>${escapeXhtml(title)}</h1>\n")
            append("  <div class=\"meta\">\n")
            if (author != null) append("    <span>By ${escapeXhtml(author)}</span> · ")
            if (siteName != null) append("    <span>${escapeXhtml(siteName)}</span> · ")
            append(
                "<a href=\"${escapeXhtml(payload.url)}\" rel=\"noopener noreferrer\" target=\"_blank\">Original Article</a>\n"
            )
            if (articleDate.isNotEmpty()) append("    <span> · ${escapeXhtml(articleDate.take(10))}</span>")
            append("\n  </div>\n")
            append("  <article>\n")
            append("    $sanitized\n")
            append("  </article>\n")
            append("</body>\n")
            append("</html>")
        }

        val contentHash = sha256Hex(cleanIndexHtml.toByteArray(Charsets.UTF_8))

        // 3. Build the EPUB 3 reading edition
        val readingEpub = buildArticleEpub(
            lang = lang,
            title = title,
            author = author,
            url = payload.url,
            siteName = siteName,
            contentHtml = sanitized,
            images = namedImages,
            publishedAt = payload.publishedAt,
            contentHash = contentHash
        )

        return ProcessedArticle(cleanIndexHtml, contentHash, namedImages, readingEpub)
    }

    /**
     * Builds an EPUB 3 zip: mimetype stored uncompressed and first (spec §6 item 4).
     */
    fun buildArticleEpub(
        lang: String,
        title: String,
        author: String?,
        url: String,
        siteName: String?,
        contentHtml: String,
        images: List<ImportImage>,
        publishedAt: String?,
        contentHash: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // mimetype FIRST and STORED (level 0)
            put(zip, "mimetype", "application/epub+zip".toByteArray(Charsets.UTF_8), stored = true)

            put(
                zip, "META-INF/container.xml",
                """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>""".toByteArray(Charsets.UTF_8)
            )

            put(
                zip, "OEBPS/style.css",
                """
body { font-family: serif; line-height: 1.6; margin: 5%; color: #1C1B19; }
h1, h2 { color: #2F6F5E; }
.meta { font-size: 0.85em; color: #6B6860; margin-bottom: 1.5em; }
img { max-width: 100%; height: auto; }
""".toByteArray(Charsets.UTF_8)
            )

            val xhtmlBody = toXhtml(contentHtml)
            val chapterXhtml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${escapeXhtml(lang)}" lang="${escapeXhtml(lang)}">
<head>
  <title>${escapeXhtml(title)}</title>
  <link rel="stylesheet" type="text/css" href="style.css" />
</head>
<body>
  <h1>${escapeXhtml(title)}</h1>
  <div class="meta">
    ${author?.let { "<span>By ${escapeXhtml(it)}</span> · " } ?: ""}
    <a href="${escapeXhtml(url)}">${escapeXhtml(siteName ?: "Source")}</a>
  </div>
  $xhtmlBody
</body>
</html>"""
            put(zip, "OEBPS/chapter_1.xhtml", chapterXhtml.toByteArray(Charsets.UTF_8))

            put(
                zip, "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" lang="${escapeXhtml(lang)}">
<head>
  <title>Table of Contents</title>
</head>
<body>
  <nav epub:type="toc" id="toc">
    <h1>Table of Contents</h1>
    <ol>
      <li><a href="chapter_1.xhtml">${escapeXhtml(title)}</a></li>
    </ol>
  </nav>
</body>
</html>""".toByteArray(Charsets.UTF_8)
            )

            val manifestItems = mutableListOf(
                "<item id=\"style\" href=\"style.css\" media-type=\"text/css\" />",
                "<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\" />",
                "<item id=\"chapter_1\" href=\"chapter_1.xhtml\" media-type=\"application/xhtml+xml\" />"
            )
            images.forEachIndexed { i, img ->
                val mime = when {
                    img.name.endsWith(".png") -> "image/png"
                    img.name.endsWith(".webp") -> "image/webp"
                    img.name.endsWith(".gif") -> "image/gif"
                    else -> "image/jpeg"
                }
                manifestItems += "<item id=\"img_${i + 1}\" href=\"images/${img.name}\" media-type=\"$mime\" />"
                put(zip, "OEBPS/images/${img.name}", img.bytes, stored = true)
            }

            val stableUuid = "urn:uuid:" + listOf(
                contentHash.substring(0, 8),
                contentHash.substring(8, 12),
                contentHash.substring(12, 16),
                contentHash.substring(16, 20),
                contentHash.substring(20, 32)
            ).joinToString("-")

            val modifiedDate = publishedAt?.let { p ->
                runCatching { java.time.Instant.parse(p).toString() }.getOrNull()
            } ?: "2024-01-01T00:00:00Z"

            put(
                zip, "OEBPS/content.opf",
                """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="pub-id">$stableUuid</dc:identifier>
    <dc:title>${escapeXhtml(title)}</dc:title>
    <dc:language>${escapeXhtml(lang)}</dc:language>
    ${author?.let { "<dc:creator>${escapeXhtml(it)}</dc:creator>" } ?: ""}
    <meta property="dcterms:modified">$modifiedDate</meta>
  </metadata>
  <manifest>
    ${manifestItems.joinToString("\n    ")}
  </manifest>
  <spine>
    <itemref idref="chapter_1" />
  </spine>
</package>""".toByteArray(Charsets.UTF_8)
            )
        }
        return out.toByteArray()
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray, stored: Boolean = false) {
        val entry = ZipEntry(name)
        if (stored) {
            entry.method = ZipEntry.STORED
            entry.size = bytes.size.toLong()
            entry.compressedSize = bytes.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(bytes)
            entry.crc = crc.value
        }
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }
}
