package dev.folio.mobile.core.importer

import dev.folio.mobile.core.model.DocMeta
import kotlinx.serialization.Serializable

/**
 * Port of the ImportResult contract (packages/core/src/import/epub.ts).
 * Importers are pure functions from bytes to this result; saving via the
 * FileStore is a separate LibraryManager step shared by all importers.
 *
 * [meta] uses DocMeta with id/slug/importedAt/files defaulted — LibraryManager
 * fills those in at commit time (TS: Omit<DocMeta, "id"|"slug"|"importedAt"|"files">).
 */
@Serializable
data class ImportImage(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ImportImage && other.name == name && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

@Serializable
data class ImportOriginal(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ImportOriginal && other.name == name && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

@Serializable
data class ImportReading(val name: String, val bytes: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is ImportReading && other.name == name && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = 31 * name.hashCode() + bytes.contentHashCode()
}

@Serializable
data class SearchChapterText(
    val chapterId: String,
    val title: String,
    val text: String
)

@Serializable
data class ImportResult(
    val meta: DocMeta,
    val original: ImportOriginal,
    val clean: ImportClean? = null,
    val reading: ImportReading? = null,
    val searchText: List<SearchChapterText> = emptyList()
)

@Serializable
data class ImportClean(
    val indexHtml: String,
    val images: List<ImportImage> = emptyList()
)
