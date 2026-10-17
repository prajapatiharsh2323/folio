package dev.folio.mobile.core.importer

import dev.folio.mobile.core.model.Chapter
import dev.folio.mobile.core.model.DocKind
import dev.folio.mobile.core.model.DocMeta
import dev.folio.mobile.core.model.SourceFormat
import dev.folio.mobile.core.util.sha256Hex
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Port of packages/core/src/import/pdf.ts (spec §6 PDF importer):
 * - title/author from Info dictionary, fallback to file name
 * - one chapter per page ("Page N", href "#page=N")
 * - average under 50 chars/page → no text layer → searchable: false
 * - password-protected files → "This PDF is locked"
 *
 * No reading/clean editions are produced for PDF (reader views the original
 * bytes through pdfium) — same shape as the PWA's ImportResult.
 *
 * NOTE (device runtime): call PDFBoxResourceLoader.init(context) once in the
 * Application class before using this importer.
 */
object PdfImporter {

    class PdfLockedException(message: String = "This PDF is locked") : Exception(message)

    fun import(bytes: ByteArray, originalName: String = "document.pdf"): ImportResult {
        val doc: PDDocument = try {
            PDDocument.load(bytes)
        } catch (e: InvalidPasswordException) {
            throw PdfLockedException()
        }
        doc.use { pdf ->
            var title = originalName.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "")
            var author: String? = null
            runCatching {
                val info = pdf.documentInformation
                info.title?.trim()?.takeIf { it.isNotEmpty() }?.let { title = it }
                info.author?.trim()?.takeIf { it.isNotEmpty() }?.let { author = it }
            }

            val numPages = pdf.numberOfPages
            val chapters = mutableListOf<Chapter>()
            val searchTextList = mutableListOf<SearchChapterText>()
            val pageTexts = mutableListOf<String>()
            var totalCharacters = 0
            if (numPages > 0) {
                val stripper = PDFTextStripper()
                for (pageNum in 1..numPages) {
                    stripper.startPage = pageNum
                    stripper.endPage = pageNum
                    val pageText = stripper.getText(pdf) // DEBUG: surface exceptions
                    pageTexts += pageText
                    totalCharacters += pageText.trim().length
                    val chapterId = "page-$pageNum"
                    val chTitle = "Page $pageNum"
                    chapters += Chapter(chapterId, chTitle, order = pageNum, href = "#page=$pageNum")
                    searchTextList += SearchChapterText(chapterId, chTitle, pageText)
                }
            }

            // Average under 50 characters a page means no text layer (spec §6)
            val avgChars = if (numPages > 0) totalCharacters.toDouble() / numPages else 0.0
            val searchable = avgChars >= 50

            return ImportResult(
                meta = DocMeta(
                    id = "pending",
                    slug = "pending",
                    title = title,
                    author = author,
                    kind = DocKind.Book,
                    language = "en",
                    sourceFormat = SourceFormat.Pdf,
                    capturedAt = java.time.Instant.now().toString(),
                    importedAt = "pending",
                    contentHash = sha256Hex(bytes),
                    searchable = searchable,
                    tags = emptyList(),
                    collectionIds = emptyList(),
                    chapters = chapters
                ),
                original = ImportOriginal(originalName, bytes),
                searchText = searchTextList
            )
        }
    }
}
