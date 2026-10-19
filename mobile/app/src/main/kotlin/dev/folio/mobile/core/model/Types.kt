package dev.folio.mobile.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Schema-1 library data model — direct Kotlin port of packages/core/src/types.ts.
 *
 * BYTE-COMPATIBILITY CONTRACT with the PWA (see .docs/MOBILE-TRACKING.md):
 * JSON field names, optionality, and enum string values MUST stay identical to the
 * TypeScript source so that backup ZIPs, manifest.json, and annotations round-trip
 * between the PWA and this app. Never rename a field here without a fixture update
 * in BOTH suites (same commit, per MOBILE-TRACKING policy).
 */
@Serializable
enum class DocKind {
    @SerialName("article") Article,
    @SerialName("book") Book;

    companion object {
        fun fromValue(raw: String): DocKind = when (raw) {
            "article" -> Article
            "book" -> Book
            else -> throw IllegalArgumentException("Unknown DocKind: $raw")
        }
    }
}

@Serializable
enum class SourceFormat {
    @SerialName("epub") Epub,
    @SerialName("pdf") Pdf,
    @SerialName("html") Html,
    @SerialName("txt") Txt,
    @SerialName("clip") Clip;

    companion object {
        fun fromValue(raw: String): SourceFormat = when (raw) {
            "epub" -> Epub
            "pdf" -> Pdf
            "html" -> Html
            "txt" -> Txt
            "clip" -> Clip
            else -> throw IllegalArgumentException("Unknown SourceFormat: $raw")
        }
    }
}

@Serializable
enum class AnnotationKind {
    @SerialName("highlight") Highlight,
    @SerialName("note") Note,
    @SerialName("bookmark") Bookmark;

    companion object {
        fun fromValue(raw: String): AnnotationKind = when (raw) {
            "highlight" -> Highlight
            "note" -> Note
            "bookmark" -> Bookmark
            else -> throw IllegalArgumentException("Unknown AnnotationKind: $raw")
        }
    }
}

@Serializable
enum class HighlightColor {
    @SerialName("yellow") Yellow,
    @SerialName("green") Green,
    @SerialName("blue") Blue,
    @SerialName("pink") Pink;

    companion object {
        fun fromValue(raw: String): HighlightColor = when (raw) {
            "yellow" -> Yellow
            "green" -> Green
            "blue" -> Blue
            "pink" -> Pink
            else -> throw IllegalArgumentException("Unknown HighlightColor: $raw")
        }
    }
}

@Serializable
data class Chapter(
    val id: String,
    val title: String,
    val order: Int,
    /** href relative to the doc folder */
    val href: String
)

@Serializable
data class DocMeta(
    val schema: Int = 1,
    /** crypto.randomUUID() */
    val id: String,
    val slug: String,
    val title: String,
    val author: String? = null,
    val kind: DocKind,
    /** BCP 47, e.g. 'en', 'hi' */
    val language: String? = null,
    val sourceFormat: SourceFormat,
    /** clips and web-origin files */
    @SerialName("sourceUrl") val sourceUrl: String? = null,
    /** site or publisher name */
    @SerialName("sourceName") val sourceName: String? = null,
    /** ISO 8601 UTC */
    val capturedAt: String,
    /** ISO 8601 UTC */
    val importedAt: String,
    /** SHA-256 hex of the original bytes (clips: of clean/index.html) */
    val contentHash: String,
    val wordCount: Int? = null,
    /** false for scanned PDFs without a text layer */
    val searchable: Boolean = true,
    val tags: List<String> = emptyList(),
    val collectionIds: List<String> = emptyList(),
    val files: DocFiles = DocFiles(),
    val chapters: List<Chapter> = emptyList()
)

@Serializable
data class DocFiles(
    val original: String? = null,
    val clean: String? = null,
    val reading: String? = null,
    val cover: String? = null
)

@Serializable
data class ProgressPace(
    val sessionsMs: Long = 0,
    val percentRead: Double = 0.0
)

@Serializable
data class Progress(
    val docId: String,
    /** EPUB CFI, PDF page, or scroll offset */
    val location: String,
    val percent: Double,
    /** ISO 8601 UTC */
    val updatedAt: String,
    /** Optional reading pace (PWA B3). Tolerated on read; written only when known. */
    val pace: ProgressPace? = null
)

@Serializable
data class Annotation(
    val id: String,
    val docId: String,
    val chapterId: String? = null,
    val quote: String,
    /** 32 characters of context before the quote */
    val prefix: String,
    /** 32 characters of context after the quote */
    val suffix: String,
    /** engine-specific hint; quote+context is authoritative */
    val location: String? = null,
    val kind: AnnotationKind,
    val color: HighlightColor? = null,
    val note: String? = null,
    /** ISO 8601 UTC */
    val createdAt: String,
    /** ISO 8601 UTC */
    val updatedAt: String
)

@Serializable
data class Collection(
    val id: String,
    val name: String,
    val docIds: List<String> = emptyList(),
    val createdAt: String
)

@Serializable
data class ManifestFile(
    val path: String,
    val size: Long,
    val sha256: String
)

@Serializable
data class ManifestDoc(
    val id: String,
    val slug: String,
    val title: String,
    val contentHash: String,
    val updatedAt: String,
    val files: List<ManifestFile> = emptyList()
)

@Serializable
data class Manifest(
    val schema: Int = 1,
    val app: String = "folio",
    val libraryId: String,
    val updatedAt: String,
    val docs: List<ManifestDoc> = emptyList()
)

/** user/settings.json shape (reader prefs; schema-1 user files) */
@Serializable
data class ReadingPrefs(
    val theme: String = "light",
    val fontFamily: String = "serif",
    val fontSize: Double = 18.0,
    val lineHeight: Double = 1.6,
    val margin: Double = 20.0,
    val justify: Boolean = false,
    val hyphenate: Boolean = false,
    // Audit EPUB-011: letterSpacing + paragraphSpacing are byte-compat schema
    // fields that now map 1:1 (em) into the Readium navigator (legend:
    // ReaderPrefsMapper → ReaderPrefsAndroid).
    val paragraphSpacing: Double = 0.0, // em
    val letterSpacing: Double = 0.0, // em
    // maxWidth stays byte-compat but is legacy-inert on mobile: it is the PWA
    // reader's desktop text-column cap (px). Android's Readium navigator has
    // no max-width preference, and injecting the CSS variable into the WebView
    // would need on-device verification (audit EPUB-019) — deferred until that
    // path exists.
    val maxWidth: Double = 720.0,
    val brightness: Double = 100.0,
    val flow: String = "paginated",
    val spread: String = "none",
    // pdfZoom stays byte-compat but is commitment-dead: PDF support was
    // deliberately removed (see ImportDispatch). Candidate for removal in a
    // cross-repo schema revision only (dropping it breaks PWA round-trips).
    val pdfZoom: String = "fit-width"
)
