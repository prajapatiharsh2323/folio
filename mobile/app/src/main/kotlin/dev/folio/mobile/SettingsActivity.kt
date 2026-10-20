package dev.folio.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.backup.BackupRestore
import dev.folio.mobile.core.backup.exportBackupZip
import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.backup.generateBackupFilename
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.PrefsStore
import dev.folio.mobile.core.store.directoryBytes
import dev.folio.mobile.core.store.formatBytes
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioSectionHeader
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.sync.CloudSyncSection
import dev.folio.mobile.ui.theme.FolioMobileTheme
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FolioMobileTheme {
                SettingsScreen()
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}

/** Display labels for stored theme keys (serialized keys are unchanged). */
private val THEME_OPTIONS = listOf(
    ThemeOption("light", "Light", 0xFFFAFAF7, 0xFF1C1B19),
    ThemeOption("warm", "Paper", 0xFFF4ECD8, 0xFF3B3226),
    ThemeOption("dark", "Dark", 0xFF121214, 0xFFE6E3DC),
    ThemeOption("true-black", "True black", 0xFF000000, 0xFFC8C4BC),
    ThemeOption("system", "System", 0xFFFAFAF7, 0xFF1C1B19, isSystem = true),
)

private data class ThemeOption(
    val key: String,
    val label: String,
    val pageArgb: Long,
    val inkArgb: Long,
    val isSystem: Boolean = false,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefsStore = remember { PrefsStore(AppFileStore(context.filesDir)) }
    val writes = remember { Channel<ReadingPrefs>(Channel.UNLIMITED) }

    var prefs by remember { mutableStateOf(ReadingPrefs()) }
    var persistedPrefs by remember { mutableStateOf(ReadingPrefs()) }
    var prefsLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var storage by remember { mutableStateOf<StorageInfo?>(null) }

    /** Picked backup URI awaiting the merge/replace mode choice. */
    var restoreTarget by remember { mutableStateOf<Uri?>(null) }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        try {
            val loaded = withContext(Dispatchers.IO) { prefsStore.loadReadingPrefs() }
            prefs = loaded
            persistedPrefs = loaded
        } catch (error: Exception) {
            showMessage("Couldn't load saved settings. Defaults are shown.")
        } finally {
            prefsLoaded = true
        }
    }

    // One writer, strictly ordered. Rapid taps cannot persist out of order, and
    // a failed write reverts only when no newer edit is already queued.
    LaunchedEffect(prefsLoaded) {
        if (!prefsLoaded) return@LaunchedEffect
        while (true) {
            val next = writes.receive()
            try {
                withContext(Dispatchers.IO) { prefsStore.saveReadingPrefs(next) }
                persistedPrefs = next
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (prefs == next) prefs = persistedPrefs
                showMessage("Couldn't save settings. Check free space and try again.")
            }
        }
    }

    fun updatePrefs(next: ReadingPrefs) {
        if (!prefsLoaded) return
        prefs = next
        writes.trySend(next)
    }

    LaunchedEffect(Unit) {
        storage = withContext(Dispatchers.IO) { computeStorage(context) }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreTarget = uri }

    fun performRestore(target: Uri, mode: RestoreMode) {
        if (busy) return
        restoreTarget = null
        busy = true
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) { restoreBackup(context, target, mode) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "Restore couldn't finish. Check the ZIP and try again."
            }
            busy = false
            showMessage(message)
            storage = withContext(Dispatchers.IO) { computeStorage(context) }
        }
    }

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) restoreTarget = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "Merge adds missing items and keeps local data. Replace " +
                        "wipes this library first. Both use the newest notes/progress."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled = !busy, onClick = { performRestore(target, RestoreMode.Merge) }) {
                        Text("Merge")
                    }
                    FolioDestructiveTextButton(
                        text = "Replace",
                        enabled = !busy,
                        onClick = { performRestore(target, RestoreMode.Replace) },
                    )
                    TextButton(enabled = !busy, onClick = { restoreTarget = null }) { Text("Cancel") }
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding(),
            contentPadding = PaddingValues(bottom = FolioSpacing.x8),
        ) {
            item("reading-header") { FolioSectionHeader(text = "Reading") }
            item("reading-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    if (!prefsLoaded) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(112.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.4f))
                        }
                    } else {
                        DefaultThemePicker(
                            prefs = prefs,
                            onSelect = { key -> updatePrefs(prefs.copy(theme = key)) },
                        )
                        Text(
                            "Default for new books. Changes made inside a book stay with that book.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = FolioSpacing.x4,
                                end = FolioSpacing.x4,
                                top = FolioSpacing.x2,
                                bottom = FolioSpacing.x3,
                            ),
                        )
                    }
                }
            }

            item("backup-header") { FolioSectionHeader(text = "Backup") }
            item("backup-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    if (busy) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
                        )
                    }
                    FolioSettingsRow(
                        title = "Export backup",
                        summary = "A ZIP of your library in Folio's backups folder.",
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    val message = try {
                                        withContext(Dispatchers.IO) { exportBackup(context) }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        "Couldn't write a backup. Check free space and try again."
                                    }
                                    busy = false
                                    showMessage(message)
                                }
                            }
                        },
                    )
                    FolioSettingsRow(
                        title = "Restore backup",
                        summary = "Import a ZIP, then choose merge or replace.",
                        onClick = {
                            if (!busy) restoreLauncher.launch(arrayOf("application/zip", "*/*"))
                        },
                    )
                }
            }

            item("cloud-header") { FolioSectionHeader(text = "Cloud sync") }
            item("cloud-group") {
                Box(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    CloudSyncSection()
                }
            }

            item("storage-header") { FolioSectionHeader(text = "Storage") }
            item("storage-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    val current = storage
                    FolioSettingsRow(
                        title = "Library size",
                        value = current?.let { formatBytes(it.libraryBytes) } ?: "…",
                        summary = "Books, notes, progress and search index.",
                    )
                    FolioSettingsRow(
                        title = "Folio data",
                        value = current?.let { formatBytes(it.appBytes) } ?: "…",
                        summary = "Library plus local settings and sync state.",
                    )
                    FolioSettingsRow(
                        title = "Free space",
                        value = current?.let { formatBytes(it.freeBytes) } ?: "…",
                    )
                }
            }

            item("about-header") { FolioSectionHeader(text = "About") }
            item("about-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    FolioSettingsRow(
                        title = "App version",
                        value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultThemePicker(
    prefs: ReadingPrefs,
    onSelect: (String) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x3),
        contentPadding = PaddingValues(
            start = FolioSpacing.x4,
            end = FolioSpacing.x4,
            top = FolioSpacing.x3,
        ),
        modifier = Modifier.selectableGroup(),
    ) {
        items(THEME_OPTIONS, key = { it.key }) { option ->
            val selected = prefs.theme == option.key
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option.key) },
                    )
                    .padding(bottom = FolioSpacing.x2),
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(option.pageArgb),
                    border = BorderStroke(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        },
                    ),
                    modifier = Modifier
                        .size(width = 56.dp, height = 44.dp),
                ) {
                    if (option.isSystem) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFFFAFAF7)),
                            )
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(Color(0xFF121214)),
                            )
                        }
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                "Aa",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    color = Color(option.inkArgb),
                                ),
                            )
                        }
                    }
                    if (selected) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(4.dp),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Text(
                                "✓",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                Text(
                    option.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier.padding(top = FolioSpacing.x1),
                )
            }
        }
    }
}

private data class StorageInfo(
    val libraryBytes: Long,
    val appBytes: Long,
    val freeBytes: Long,
)

private fun computeStorage(context: Context): StorageInfo {
    val files = context.filesDir
    return StorageInfo(
        libraryBytes = directoryBytes(File(files, "library")),
        appBytes = directoryBytes(files),
        freeBytes = StatFs(files.absolutePath).availableBytes,
    )
}

/**
 * Streams a verified, PWA-compatible backup ZIP (entries added one file at a
 * time — the whole library never sits in memory). Caller is on Dispatchers.IO.
 */
private suspend fun exportBackup(context: Context): String {
    val store = AppFileStore(context.filesDir)
    val outDir = File(context.getExternalFilesDir(null), "backups").apply { mkdirs() }
    val out = File(outDir, generateBackupFilename())
    FileOutputStream(out).use { fos ->
        exportBackupZip(store, fos)
        fos.flush()
    }
    return "Backup saved: ${out.name}"
}

/**
 * Verified restore via BackupRestore (merge/replace rules ported from the PWA).
 * The archive is staged to a temp file and streamed per entry. Caller is on
 * Dispatchers.IO.
 */
private suspend fun restoreBackup(context: Context, uri: Uri, mode: RestoreMode): String {
    val store = AppFileStore(context.filesDir)
    val library = LibraryManager(store)
    val input = context.contentResolver.openInputStream(uri)
        ?: throw IllegalStateException("Cannot read backup")
    return input.use { stream ->
        val (summary, verification) = BackupRestore.restore(store, library, stream, context.cacheDir, mode)
        if (!verification.valid) {
            "Restore completed with warnings: " +
                verification.issues.joinToString("; ") { "${it.path} (${it.reason})" }
        } else {
            "${summary.mode.replaceFirstChar { it.uppercase() }}: +${summary.newDocsAdded} new, " +
                "${summary.duplicatesSkipped} duplicates skipped, " +
                "${summary.metadataUpdated} updated, " +
                "${summary.annotationsMerged} annotations, " +
                "${summary.progressUpdated} progress, " +
                "${summary.collectionsMerged} collections"
        }
    }
}
