@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.folio.mobile.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.model.Collection
import dev.folio.mobile.ui.theme.FolioDimensions
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Pauses a beat so the sheet's enter animation settles before stealing focus. */
private suspend fun sheetFocus(requester: FocusRequester) {
    delay(250)
    runCatching { requester.requestFocus() }
}

/**
 * New collection sheet: auto-focused name field (the fixed [FolioTextField],
 * always full-width and tappable) + Cancel/Create. [onCreate] returns null
 * when the store rejects the name so the sheet can stay open with the text
 * intact. The sheet rides above the IME so the focused field and the Create
 * button stay reachable on short devices, Done submits the trimmed name, and
 * the busy flag guards against duplicate submissions.
 */
@Composable
fun CreateCollectionSheet(
    onCreate: suspend (String) -> Collection?,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun create() {
        val trimmed = name.trim()
        if (creating || trimmed.isEmpty()) return
        creating = true
        scope.launch {
            val created = onCreate(trimmed)
            creating = false
            if (created != null) {
                keyboard?.hide()
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(title = "New collection", onClose = onDismiss)
            Column(
                modifier = Modifier.padding(horizontal = FolioSpacing.x6),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x3),
            ) {
                FolioTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Collection name…",
                    label = "Collection name",
                    focusRequester = focusRequester,
                    onDone = { create() },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x2),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    FolioFilledButton(
                        text = if (creating) "Creating…" else "Create",
                        enabled = !creating && name.isNotBlank(),
                        onClick = {
                            if (!creating) create()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { sheetFocus(focusRequester) }
}

/** Rename collection sheet: prefilled field; blank/unchanged names are a no-op. */
@Composable
fun RenameCollectionSheet(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(currentName) }
    val clean = name.trim()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(title = "Rename collection", onClose = onDismiss)
            Column(
                modifier = Modifier.padding(horizontal = FolioSpacing.x6),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x3),
            ) {
                FolioTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "Collection name…",
                    label = "Collection name",
                    focusRequester = focusRequester,
                    onDone = {
                        if (clean.isNotEmpty() && clean != currentName) {
                            keyboard?.hide()
                            onSave(clean)
                            onDismiss()
                        } else {
                            keyboard?.hide()
                        }
                    },
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x2),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    FolioFilledButton(
                        text = "Save",
                        enabled = clean.isNotEmpty() && clean != currentName,
                        onClick = {
                            onSave(clean)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { sheetFocus(focusRequester) }
}

/**
 * Collection picker ("Add to collection…" from the library): checkbox list of
 * collections + quick-create row; [onApply] receives the final checked ids.
 * The parent decides the write mode (exact sync for one book, bulk add for
 * multi-select), which keeps every PWA dual-write invariant in one place.
 */
@Composable
fun CollectionPickerSheet(
    collections: List<Collection>,
    initialChecked: Set<String>,
    selectionLabel: String?,
    onApply: (Set<String>) -> Unit,
    onCreate: (name: String, onCreated: (Collection) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember { mutableStateOf(initialChecked) }
    var newCollName by remember { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(
                title = "Add to collection",
                subtitle = selectionLabel,
                onClose = onDismiss,
            )
            if (collections.isEmpty()) {
                Text(
                    "No collections yet — create one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x3),
                )
            } else {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outline)
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(collections, key = { _, c -> c.id }) { _, coll ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Whole-row tap toggles the checkbox (PWA parity).
                                    checked = if (coll.id in checked) {
                                        checked - coll.id
                                    } else {
                                        checked + coll.id
                                    }
                                }
                                .padding(horizontal = FolioSpacing.x6)
                                .heightIn(min = FolioDimensions.touchTarget),
                        ) {
                            Checkbox(
                                checked = coll.id in checked,
                                onCheckedChange = { nowChecked ->
                                    checked = if (nowChecked) checked + coll.id else checked - coll.id
                                },
                            )
                            Text(
                                coll.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = FolioSpacing.x2),
                            )
                        }
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = FolioSpacing.x12),
                        )
                    }
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = FolioSpacing.x2),
            )
            Column(
                modifier = Modifier.padding(horizontal = FolioSpacing.x6),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FolioTextField(
                        value = newCollName,
                        onValueChange = { newCollName = it },
                        placeholder = "New collection…",
                        label = "New collection name",
                        modifier = Modifier.weight(1f),
                        focusRequester = focusRequester.takeIf { collections.isEmpty() },
                        onDone = { keyboard?.hide() },
                    )
                    TextButton(
                        enabled = !creating,
                        onClick = {
                            val name = newCollName.trim()
                            if (name.isNotEmpty() && !creating) {
                                onCreate(name) { created ->
                                    checked = checked + created.id
                                }
                                newCollName = ""
                            }
                        },
                    ) { Text("Create") }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x2),
                ) {
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    ) { Text("Cancel") }
                    FolioFilledButton(
                        text = "Add",
                        onClick = {
                            onApply(checked)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    if (collections.isEmpty()) {
        LaunchedEffect(Unit) { sheetFocus(focusRequester) }
    }
}

/** One selectable book row inside [BookPickerSheet]. */
data class PickableBook(
    val id: String,
    val title: String,
    val author: String,
    val coverPath: String?,
    val seed: Int,
    /** Already a member: shown as "Added", not toggleable (picker only adds). */
    val alreadyAdded: Boolean,
)

/**
 * "Add books" picker for a collection: search + multi-select, books already in
 * the collection are shown but not toggleable. Confirm adds the newly checked
 * ones only — unchecking can't accidentally remove, matching the picker's
 * "Add" semantics.
 */
@Composable
fun BookPickerSheet(
    books: List<PickableBook>,
    onConfirm: (docIds: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    val filtered = if (query.isBlank()) books else books.filter { book ->
        book.title.contains(query, ignoreCase = true) ||
            book.author.contains(query, ignoreCase = true)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(bottom = FolioSpacing.x6),
        ) {
            FolioSheetHeader(title = "Add books", onClose = onDismiss)
            Column(
                modifier = Modifier.padding(horizontal = FolioSpacing.x6),
                verticalArrangement = Arrangement.spacedBy(FolioSpacing.x3),
            ) {
                FolioSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search your library…",
                    focusRequester = focusRequester,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = FolioSpacing.x3),
                ) {
                    Text(
                        when (val n = selected.size) {
                            0 -> "Select books to add"
                            1 -> "1 book selected"
                            else -> "$n books selected"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(
                thickness = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(top = FolioSpacing.x2),
            )
            if (filtered.isEmpty()) {
                Text(
                    if (query.isBlank()) "Your library is empty." else "No books match “$query”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x6),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(filtered, key = { _, book -> book.id }) { _, book ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x1)
                                .heightIn(min = FolioDimensions.touchTarget)
                                .clickable(enabled = !book.alreadyAdded) {
                                    selected = if (book.id in selected) {
                                        selected - book.id
                                    } else {
                                        selected + book.id
                                    }
                                },
                        ) {
                            BookCover(
                                coverPath = book.coverPath,
                                seed = book.seed,
                                title = book.title,
                                width = 40.dp,
                                height = 56.dp,
                            )
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = FolioSpacing.x3),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    book.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    if (book.alreadyAdded) "Added" else book.author,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (book.alreadyAdded) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Checkbox(
                                checked = book.alreadyAdded || book.id in selected,
                                enabled = !book.alreadyAdded,
                                onCheckedChange = { now ->
                                    selected = if (now) selected + book.id else selected - book.id
                                },
                            )
                        }
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(start = FolioSpacing.x12),
                        )
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x3),
            ) {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                ) { Text("Cancel") }
                FolioFilledButton(
                    text = "Add " + if (selected.size == 1) "1 book" else "${selected.size} books",
                    enabled = selected.isNotEmpty(),
                    onClick = {
                        onConfirm(selected.toList())
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    LaunchedEffect(Unit) { sheetFocus(focusRequester) }
}
