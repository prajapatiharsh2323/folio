package dev.folio.mobile.core.annotation

import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind
import dev.folio.mobile.core.model.HighlightColor
import org.readium.r2.shared.publication.Locator

/**
 * Builds schema-1 [Annotation]s from a Readium selection.
 *
 * BYTE-COMPAT CONTRACT (apps/web/src/reader/epub-engine.ts):
 * - `prefix` = last 32 chars of the text before the quote
 * - `suffix` = first 32 chars of the text after the quote
 * - `location` = CFI string (engine-specific hint; quote+context authoritative)
 *
 * The primitive core is JVM-testable; the [Locator] overload is a thin wrapper
 * (Locator/Url values need the Android runtime — see ProgressBridge KDoc).
 */
object SelectionBridge {

    const val CONTEXT_CHARS = 32

    fun annotationFrom(
        docId: String,
        quote: String,
        before: String,
        after: String,
        location: String?,
        id: String,
        updatedAt: String,
        color: HighlightColor = HighlightColor.Yellow
    ): Annotation = Annotation(
        id = id,
        docId = docId,
        chapterId = null,
        quote = quote,
        prefix = before.takeLast(CONTEXT_CHARS),
        suffix = after.take(CONTEXT_CHARS),
        location = location,
        kind = AnnotationKind.Highlight,
        color = color,
        note = null,
        createdAt = updatedAt,
        updatedAt = updatedAt
    )

    fun annotationFromSelection(
        docId: String,
        locator: Locator,
        id: String,
        updatedAt: String,
        color: HighlightColor = HighlightColor.Yellow
    ): Annotation {
        val text = locator.text
        return annotationFrom(
            docId = docId,
            quote = text.highlight.orEmpty(),
            before = text.before.orEmpty(),
            after = text.after.orEmpty(),
            location = dev.folio.mobile.core.reader.ProgressBridge.locationString(locator),
            id = id,
            updatedAt = updatedAt,
            color = color
        )
    }
}
