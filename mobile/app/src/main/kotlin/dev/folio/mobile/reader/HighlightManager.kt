package dev.folio.mobile.reader

import dev.folio.mobile.core.annotation.AnnotationLocators
import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.HighlightColor
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.mediatype.MediaType

/**
 * Highlight decoration pipeline (R3).
 *
 * Readium 3.0.0 renders highlights as `Decoration`s applied to the navigator:
 * - Decoration routing is keyed by `decoration.locator.href` (exact Url);
 * - the WebView anchors the DOM range from `locator.text`
 *   (highlight/before/after) via Hypothesis' TextQuoteAnchor — verified in
 *   toolkit 3.0.0 `assets/_scripts/src/utils.js rangeFromLocator`. NO CFI parser
 *   is required to re-render persisted annotations.
 *
 * Annotations are loaded from the schema-1 store (`library/user/annotations.json`)
 * and hydrated at attach time via [boot]; session-created ones arrive via [add].
 * Both end up in the same maps so edits and decorations behave identically.
 *
 * Group "highlights" is used for applyDecorations/addDecorationListener.
 */
class HighlightManager(
    private val navigator: EpubNavigatorFragment,
    private val publication: Publication,
    private val docId: String
) {
    companion object {
        const val GROUP = "highlights"

        /** PWA HighlightColor → ARGB (HIGHLIGHT_COLORS tokens, semi-transparent). */
        fun tintOf(color: HighlightColor?): Int = when (color) {
            HighlightColor.Green -> 0x557FC8B2
            HighlightColor.Blue -> 0x557FA8D0
            HighlightColor.Pink -> 0x55D08FA8
            else -> 0x55F5C86B // yellow
        }
    }

    /** annotationId → full Locator (rebuilt or session-captured). */
    private val locators = mutableMapOf<String, Locator>()
    private val annotations = mutableMapOf<String, Annotation>()

    /** Listener for decoration activation (context menu), set by the activity. */
    var onDecorationActivated: ((event: DecorableNavigator.OnActivatedEvent) -> Unit)? = null

    fun installListener() {
        navigator.addDecorationListener(GROUP, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                onDecorationActivated?.invoke(event)
                return true
            }
        })
    }

    /**
     * Hydrates persisted annotations at attach time. Only annotations that can
     * be anchored (targetable spine + non-empty quote) render; the rest are
     * skipped silently (PWA parity: an annotation without a resolvable anchor
     * simply doesn't paint).
     */
    suspend fun boot(saved: List<Annotation>) {
        val hrefIdx = publication.readingOrder.map { it.url().toString() }
        for (annotation in saved) {
            if (annotation.quote.isEmpty()) continue
            val spineIdx = AnnotationLocators.anchorIndex(hrefIdx, annotation.location) ?: continue
            val link = publication.readingOrder[spineIdx]
            this.annotations[annotation.id] = annotation
            locators[annotation.id] = Locator(
                href = link.url(),
                mediaType = link.mediaType ?: MediaType.XHTML,
                text = Locator.Text(
                    before = annotation.prefix,
                    highlight = annotation.quote,
                    after = annotation.suffix
                )
            )
        }
        apply()
    }

    /** Adds an annotation and re-applies the whole decoration set. */
    suspend fun add(annotation: Annotation, locator: Locator) {
        annotations[annotation.id] = annotation
        locators[annotation.id] = locator
        apply()
    }

    /** Replaces a stored annotation (edit) and re-applies decorations. */
    suspend fun update(updated: Annotation) {
        if (updated.id !in annotations) return
        annotations[updated.id] = updated
        apply()
    }

    /** Removes an annotation by id and re-applies. */
    suspend fun remove(id: String) {
        annotations.remove(id)
        locators.remove(id)
        apply()
    }

    fun annotationById(id: String): Annotation? = annotations[id]

    private suspend fun apply() {
        val decorations = annotations.keys.mapNotNull { id ->
            locators[id]?.let { locator ->
                Decoration(
                    id = id,
                    locator = locator,
                    style = Decoration.Style.Highlight(
                        tint = tintOf(annotations[id]?.color),
                        isActive = false
                    ),
                    extras = emptyMap()
                )
            }
        }
        navigator.applyDecorations(decorations, GROUP)
    }
}
