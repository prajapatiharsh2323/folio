package dev.folio.mobile.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.folio.mobile.core.dictionary.DictSense
import dev.folio.mobile.core.dictionary.DictionaryEntry
import dev.folio.mobile.core.dictionary.DictionaryUiState
import dev.folio.mobile.ui.components.FolioSheetBody
import dev.folio.mobile.ui.components.FolioSheetHeader
import dev.folio.mobile.ui.theme.FolioRadii
import dev.folio.mobile.ui.theme.FolioSpacing

/**
 * Dictionary result surface (D1): one calm, opaque bottom sheet that folds
 * into the appearance theme (MaterialTheme surfaces — same stack as the
 * annotation sheets, so book text never bleeds through). Content mirrors the
 * data contract: Hindi meanings first (target-audience-first), the source's
 * example sentence only when the pack actually carries one, a discreet
 * source/licence label, and NO fabricated fields (pronunciation rows simply
 * do not exist in this dataset).
 */

/** Dataset POS codes → readable labels (all mapped; unknown codes pass through). */
private val POS_LABELS: Map<String, String> = mapOf(
    "N" to "noun",
    "Adj" to "adjective",
    "V" to "verb",
    "VT" to "verb (transitive)",
    "VI" to "verb (intransitive)",
    "VTI" to "verb (trans./intrans.)",
    "PhrV" to "phrasal verb",
    "IDM" to "idiom",
    "Adv" to "adverb",
    "Prep" to "preposition",
    "Pron" to "pronoun",
    "Conj" to "conjunction",
    "Det" to "determiner",
    "Interj" to "interjection",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionarySheet(
    state: DictionaryUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        // Header stays OUTSIDE the scroll: on long multi-sense entries the
        // Close affordance must remain hittable while the body scrolls under
        // it (the review audit caught the header scrolling away).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(
                title = when (val s = state) {
                    is DictionaryUiState.Ready -> s.query.displayKey
                    is DictionaryUiState.NoMatch -> s.query.displayKey
                    is DictionaryUiState.Loading -> "Meaning"
                    is DictionaryUiState.Unavailable -> "Meaning"
                    is DictionaryUiState.Invalid -> "Meaning"
                },
                subtitle = when (val s = state) {
                    is DictionaryUiState.Ready -> "Offline dictionary"
                    is DictionaryUiState.NoMatch -> "Offline dictionary"
                    else -> null
                },
                onClose = onDismiss,
            )
            // Scrollable body: results of any length stay reachable and the
            // header never moves.
            FolioSheetBody {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    when (val s = state) {
                        DictionaryUiState.Loading -> LoadingBody()
                        is DictionaryUiState.Ready -> ReadyBody(
                            queryDisplay = s.query.displayKey,
                            entries = s.entries,
                            isExactMatch = s.isExactMatch,
                            dataset = s.meta.dataset,
                            datasetVersion = s.meta.datasetVersion,
                            licence = s.meta.licence,
                        )
                        is DictionaryUiState.NoMatch -> NoMatchBody(s.query.displayKey)
                        is DictionaryUiState.Unavailable -> UnavailableBody(
                            message = s.message,
                            retryable = s.retryable,
                            onRetry = onRetry,
                        )
                        is DictionaryUiState.Invalid -> InvalidBody(s.reason)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingBody() {
    LinearProgressIndicator(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FolioSpacing.x2),
    )
}

@Composable
private fun ReadyBody(
    queryDisplay: String,
    entries: List<DictionaryEntry>,
    isExactMatch: Boolean,
    dataset: String,
    datasetVersion: String,
    licence: String,
) {
    if (!isExactMatch) {
        val base = entries.firstOrNull()?.headword
        if (!base.isNullOrBlank() && !base.equals(queryDisplay, ignoreCase = true)) {
            NoticeLine("Matched the dictionary form “$base”.")
        }
    }
    entries.forEachIndexed { index, entry ->
        if (index > 0) RowsDivider()
        PosLabel(entry)
        SenseList(entry.senses)
    }
    RowsDivider()
    Text(
        "$dataset · v$datasetVersion · $licence",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FolioSpacing.x3),
    )
}

/** One POS subgroup: label chip row + senses. */
@Composable
private fun PosLabel(entry: DictionaryEntry) {
    val label = entry.partOfSpeech
        ?.let { POS_LABELS[it] ?: it.lowercase() }
        ?: "word"
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(top = FolioSpacing.x4, bottom = FolioSpacing.x1),
    )
}

@Composable
private fun SenseList(senses: List<DictSense>) {
    if (senses.isEmpty()) {
        NoSensesLine()
        return
    }
    Column {
        senses.forEachIndexed { index, sense ->
            Row(modifier = Modifier.padding(top = if (index == 0) FolioSpacing.x2 else FolioSpacing.x3)) {
                if (senses.size > 1) {
                    Text(
                        "${index + 1}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = FolioSpacing.x2),
                    )
                }
                Column {
                    if (sense.hindi.isNotEmpty()) {
                        Text(
                            sense.hindi.joinToString("  ·  "),
                            style = hindiTextStyle(),
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 26.sp,
                        )
                    }
                    sense.example?.let { example ->
                        Text(
                            "\u201C$example\u201D",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = FolioSpacing.x2),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoSensesLine() {
    Text(
        "No meaning recorded for this sense in the offline dictionary.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = FolioSpacing.x2),
    )
}

@Composable
private fun NoticeLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                shape = RoundedCornerShape(FolioRadii.control),
            )
            .padding(horizontal = FolioSpacing.x3, vertical = FolioSpacing.x2),
    )
}

@Composable
private fun NoMatchBody(query: String) {
    Column(modifier = Modifier.padding(top = FolioSpacing.x3)) {
        Text(
            "\u201C$query\u201D isn't in the offline dictionary.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "The bundled dictionary covers about 25,000 common English words" +
                " and short phrases. Try selecting a different word.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = FolioSpacing.x2),
        )
    }
}

@Composable
private fun UnavailableBody(message: String, retryable: Boolean, onRetry: () -> Unit) {
    Column(modifier = Modifier.padding(top = FolioSpacing.x3)) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (retryable) {
            TextButton(onClick = onRetry, modifier = Modifier.padding(top = FolioSpacing.x2)) {
                Text("Retry")
            }
        }
    }
}

@Composable
private fun InvalidBody(reason: String) {
    Text(
        reason,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = FolioSpacing.x3),
    )
}

@Composable
private fun RowsDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = FolioSpacing.x4, bottom = FolioSpacing.x1)
            .height(1.dp)
            .background(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
    )
}

/** Devanagari: the system font stack resolves it; size bumped for legibility. */
private fun hindiTextStyle(): TextStyle = TextStyle(
    fontSize = 18.sp,
    fontWeight = FontWeight.Medium,
)
