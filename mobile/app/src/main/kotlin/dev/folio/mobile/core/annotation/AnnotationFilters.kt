package dev.folio.mobile.core.annotation

import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.AnnotationKind

/**
 * Notes-list filters (design §B). The schema keeps a highlight-with-note as a
 * single [AnnotationKind.Highlight] record with a non-null `note`; a record can
 * therefore appear in both the Highlights and Notes filters on purpose.
 */

enum class AnnotationFilter(val label: String) {
    All("All"),
    Highlights("Highlights"),
    Notes("Notes"),
    Bookmarks("Bookmarks"),
}

/**
 * Filter semantics (stable, test-covered):
 * - `All`: every annotation;
 * - `Highlights`: highlight/note records that carry a selected passage
 *   ([AnnotationKind.Highlight] or [AnnotationKind.Note]);
 * - `Notes`: any annotation with written note text, including a highlight with
 *   a note;
 * - `Bookmarks`: saved reading locations ([AnnotationKind.Bookmark]).
 */
fun List<Annotation>.filtered(filter: AnnotationFilter): List<Annotation> =
    when (filter) {
        AnnotationFilter.All -> this
        AnnotationFilter.Highlights -> filter {
            it.kind == AnnotationKind.Highlight || it.kind == AnnotationKind.Note
        }
        AnnotationFilter.Notes -> filter { !it.note.isNullOrBlank() }
        AnnotationFilter.Bookmarks -> filter { it.kind == AnnotationKind.Bookmark }
    }
