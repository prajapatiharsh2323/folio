package dev.folio.mobile.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.ui.components.FolioSheetHeader
import dev.folio.mobile.ui.components.ReadingPrefsEditor
import dev.folio.mobile.ui.components.ReadingPagePreview
import dev.folio.mobile.ui.components.ReadingEditorSection
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Reading appearance sheet (audit P0-02): every typography control that used to
 * live in the horizontally scrolling rows, now named in plain language:
 * Theme · Text size · Typeface · Line spacing · Page margins · Alignment ·
 * Reading flow · Reader brightness, plus the per-book scope note and reset.
 *
 * S1 audit Phase 2: the control set itself lives in the SHARED
 * [ReadingPrefsEditor]; identical controls powered the (now removed) Settings
 * reading-defaults editor. M-26: with that editor gone, this sheet is also
 * the EDITING SURFACE for the global reading defaults — "Set as default for
 * future books" publishes the current effective prefs to
 * `library/user/settings.json` behind a confirm dialog (ReaderActivity owns
 * the persist + corrupt handling). This sheet adds the sheet header, the live
 * reader page preview, the defaults-publishing row, and the per-book scope
 * note/reset.
 *
 * Prefs contract (unchanged): the sheet only emits updated [ReadingPrefs] with
 * the exact serialized keys already stored ("warm"/"lh"/"mx"/... semantics are
 * untouched; only labels changed). Live updates and per-book override
 * persisting stay in ReaderActivity.
 */
@Composable
fun ReadingAppearanceSheet(
    prefs: ReadingPrefs,
    perBookOverride: Boolean,
    onPrefsChange: (ReadingPrefs) -> Unit,
    onResetPerBook: () -> Unit,
    /** M-26: saves the CURRENT effective prefs as the global reading defaults. */
    onSetDefault: () -> Unit,
    onDismiss: () -> Unit,
    /** Live preview while dragging; persistence happens through [onPrefsChange]. */
    onPreview: (ReadingPrefs) -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = FolioSpacing.x4, bottom = FolioSpacing.x8)
    ) {
        FolioSheetHeader(
            title = "Reading appearance",
            subtitle = "These settings apply to this book",
            onClose = onDismiss,
        )

        ReadingEditorSection("Theme") {
            ReadingPagePreview(
                prefs = prefs,
                modifier = Modifier.padding(horizontal = FolioSpacing.x2),
            )
        }

        ReadingPrefsEditor(
            prefs = prefs,
            onPrefsChange = onPrefsChange,
            onPreview = onPreview,
        )

        // Global-defaults editing surface (M-26): the Settings screen no
        // longer carries a reading-defaults editor, so the sheet publishes
        // the current effective combination to library/user/settings.json.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
        ) {
            Text(
                "New books without their own settings open this way.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSetDefault) { Text("Set as default") }
        }

        if (perBookOverride) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
            ) {
                Text(
                    "This book uses its own reading settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onResetPerBook) { Text("Reset to defaults") }
            }
        }
    }
}
