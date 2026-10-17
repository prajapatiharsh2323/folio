package dev.folio.mobile.core.importer

import dev.folio.mobile.core.clip.ArticlePayload
import dev.folio.mobile.core.clip.EpubBuilder
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.util.sha256Hex

object TxtImporter {

    /** True when more than 30% of letters are Devanagari (U+0900–U+097F) — spec §6. */
    fun isDevanagariDominant(text: String): Boolean {
        val lettersOnly = text.filter { it.isLetter() }
        if (lettersOnly.isEmpty()) return false
        val devanagari = lettersOnly.count { it.code in 0x0900..0x097f }
        return devanagari.toDouble() / lettersOnly.length > 0.3
    }

    /**
     * Imports a plain text file (spec §6): split on blank lines into paragraphs,
     * title from the file name, UTF-8 and UTF-8-BOM handled, Hindi detection.
     */
    fun import(bytes: ByteArray, filename: String = "document.txt"): ImportResult {
        var content = bytes.toString(Charsets.UTF_8)
        if (content.startsWith("\uFEFF")) content = content.substring(1)

        val title = filename.replace(Regex("\\.txt$", RegexOption.IGNORE_CASE), "")
        val language = if (isDevanagariDominant(content)) "hi" else "en"

        val paragraphs = content
            .split(Regex("\\r?\\n\\s*\\r?\\n"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val bodyHtml = paragraphs.joinToString("\n") { p ->
            "<p>${p.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</p>"
        }

        val processed = EpubBuilder.processArticle(
            ArticlePayload(
                url = filename,
                title = title,
                lang = language,
                contentHtml = bodyHtml
            )
        )

        return ImportResult(
            meta = DocMeta(
                id = "pending",
                slug = "pending",
                title = title,
                kind = DocKind.Article,
                language = language,
                sourceFormat = SourceFormat.Txt,
                capturedAt = java.time.Instant.now().toString(),
                importedAt = "pending",
                contentHash = sha256Hex(bytes),
                searchable = true,
                tags = emptyList(),
                collectionIds = emptyList(),
                chapters = listOf(
                    dev.folio.mobile.core.model.Chapter("ch-1", title, order = 1, href = "chapter_1.xhtml")
                )
            ),
            original = ImportOriginal(filename, bytes),
            clean = ImportClean(indexHtml = processed.cleanIndexHtml, images = emptyList()),
            reading = ImportReading("book.epub", processed.readingEpub),
            searchText = listOf(SearchChapterText("ch-1", title, content))
        )
    }
}
