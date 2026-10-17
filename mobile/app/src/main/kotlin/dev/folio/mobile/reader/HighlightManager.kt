package dev.folio.mobile.reader

import dev.folio.mobile.core.model.Annotation
import dev.folio.mobile.core.model.HighlightColor
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.preferences.Color
import org.readium.r2.shared.publication.Locator

/**
 * Session-scoped highlight pipeline (R3 groundwork).
 *
 * Readium 3.0.0 renders highlights as `Decoration`s applied to the navigator.
 * Saved annotations only carry a CFI string (schema-1 contract), which 3.0.0
 * cannot parse back into a Locator — so v1 renders only annotations created in
 * the current session (we keep their full Locator in memory). Rendering
 * persisted highlights on reopen arrives with the CFI-parser work.
 *
 * Group "highlights" is used for applyDecorations/addDecorationListener.
 */
class HighlightManager(
    private val navigator: EpubNavigatorFragment,
    private val docId: String
) {
    companion object {
        const val GROUP = "highlights"

        /** PWA HighlightColor → ARGB (semi-transparent so text stays readable). */
        fun tintOf(color: HighlightColor?): Int = when (color) {
            HighlightColor.Green -> 0x557FC8B2
            HighlightColor.Blue -> 0x557FA8D0
            HighlightColor.Pink -> 0x55D08FA8
            else -> 0x55F5C86B // yellow
        }
    }

    /** annotationId → full Locator (session-only; see class KDoc). */
    private val locators = mutableMapOf<String, Locator>()
    private val annotations = mutableMapOf<String, Annotation>()

    fun installListener() {
        navigator.addDecorationListener(GROUP, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                // R3 TODO: open the annotation context menu (edit note / color /
                // delete). v1 consumes the tap so the selection isn't lost.
                return true
            }
        })
    }

    /** Adds an annotation and re-applies the whole decoration set. */
    suspend fun add(annotation: Annotation, locator: Locator) {
        annotations[annotation.id] = annotation
        locators[annotation.id] = locator
        apply()
    }

    /** Removes an annotation by id and re-applies. */
    suspend fun remove(id: String) {
        annotations.remove(id)
        locators.remove(id)
        apply()
    }

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
