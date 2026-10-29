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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.backup.BackupRestore
import dev.folio.mobile.core.backup.exportBackupZip
import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.backup.generateBackupFilename
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.RECOVERY_DIR
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.store.AppAppearanceStore
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.PrefsStore
import dev.folio.mobile.core.store.directoryBytes
import dev.folio.mobile.core.store.formatBytes
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioSectionHeader
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.components.ReadingEditorSection
import dev.folio.mobile.ui.components.ReadingPagePreview
import dev.folio.mobile.ui.components.ReadingPrefsEditor
import dev.folio.mobile.ui.components.ReadingSegment
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioMobileTheme
import dev.folio.mobile.ui.theme.FolioRadii
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
        // App-interface appearance (S1 audit Phase 2): own store, one blocking
        // read so the first frame already carries the stored choice instead of
        // flashing the system theme. A corrupt file resolves to System here;
        // the honest corrupt banner lives in Settings where the user can act.
        val storedAppearance = AppAppearanceStore.loadThemeSync(AppFileStore(filesDir))
        setContent {
            FolioMobileTheme(
                darkTheme = AppAppearanceStore.isDark(
                    storedAppearance,
                    isSystemInDarkTheme(),
                ),
            ) {
                SettingsScreen(initialAppTheme = storedAppearance)
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(initialAppTheme: String = AppAppearanceStore.KEY_SYSTEM) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val prefsStore = remember { PrefsStore(AppFileStore(context.filesDir)) }
    val appearanceStore = remember { AppAppearanceStore(AppFileStore(context.filesDir)) }
    // CONFLATED channel: rapid slider commits leave one pending snapshot
    // (bounded writer; every drain persists the newest value).
    val writes = remember { Channel<ReadingPrefs>(Channel.CONFLATED) }

    var prefs by remember { mutableStateOf(ReadingPrefs()) }
    var persistedPrefs by remember { mutableStateOf(ReadingPrefs()) }

    /**
     * Living app-UI theme key. Starts from the synchronously loaded value;
     * changes re-theme this screen immediately and persist async.
     */
    var appTheme by remember { mutableStateOf(initialAppTheme) }

    /** Honest corrupt-state flags — %s surfaced once, plain language. */
    var readingSettingsCorrupt by remember { mutableStateOf(false) }
    var appearanceCorrupt by remember { mutableStateOf(false) }

    var prefsLoaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var storage by remember { mutableStateOf<StorageInfo?>(null) }
    var storageError by remember { mutableStateOf(false) }
    var storageReload by remember { mutableStateOf(0) }
    /** First-aid copies kept under library/recovery. */
    var recoveryCopies by remember { mutableStateOf(0) }

    /** Picked backup URI awaiting the merge/replace mode choice. */
    var restoreTarget by remember { mutableStateOf<Uri?>(null) }

    var confirmResetDefaults by remember { mutableStateOf(false) }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    LaunchedEffect(Unit) {
        // Honest reads: Missing = valid first run; Corrupt = explicit banner
        // with the recovery route, never a silent empty reset.
        try {
            val loaded = withContext(Dispatchers.IO) { prefsStore.loadReadingPrefs() }
            prefs = loaded
            persistedPrefs = loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            readingSettingsCorrupt = true
        }
        try {
            when (withContext(Dispatchers.IO) { appearanceStore.read() }) {
                is ReadResult.Loaded -> Unit // initialAppTheme already carries it
                ReadResult.Missing -> Unit
                is ReadResult.Corrupt -> appearanceCorrupt = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showMessage("Folio's appearance setting could not be read.")
        } finally {
            prefsLoaded = true
        }
    }

    // One writer, strictly ordered. A failed write reverts only when no newer
    // edit is already queued.
    LaunchedEffect(prefsLoaded) {
        if (!prefsLoaded) return@LaunchedEffect
        while (true) {
            val next = writes.receive()
            try {
                withContext(Dispatchers.IO) { prefsStore.saveReadingPrefs(next) }
                persistedPrefs = next
                // A successful save resolves a stale corrupt-settings state.
                readingSettingsCorrupt = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (prefs == next) prefs = persistedPrefs
                showMessage(
                    error.message?.takeIf { it.isNotBlank() }
                        ?: "Couldn't save settings. Check free space and try again."
                )
            }
        }
    }

    fun commitPrefs(next: ReadingPrefs) {
        if (!prefsLoaded) return
        prefs = next
        writes.trySend(next)
    }

    LaunchedEffect(storageReload) {
        storageError = false
        storage = try {
            withContext(Dispatchers.IO) { computeStorage(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            storageError = true
            null
        }
        recoveryCopies = try {
            withContext(Dispatchers.IO) { recoveryCopyCount(context) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            0
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null || busy) return@rememberLauncherForActivityResult
        busy = true
        busyLabel = "Exporting backup…"
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) { exportBackup(context, uri) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "Couldn't write a backup. ${error.message ?: "Check free space and try again."}"
            }
            busy = false
            busyLabel = null
            showMessage(message)
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) restoreTarget = uri }

    fun performRestore(target: Uri, mode: RestoreMode) {
        if (busy) return
        restoreTarget = null
        busy = true
        busyLabel = "Restoring backup…"
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) { restoreBackup(context, target, mode) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "Restore couldn't finish. ${error.message ?: "Check the ZIP and try again."}"
            }
            busy = false
            busyLabel = null
            showMessage(message)
            storageReload++
        }
    }

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!busy) restoreTarget = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "The ZIP can include books, the library manifest, annotations, " +
                        "reading progress, collections and the global reading settings.json. " +
                        "Merge adds missing items and keeps local data. Replace wipes this " +
                        "library first (a rollback copy is kept if anything fails). Both use " +
                        "the newest notes/progress."
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(
                        enabled = !busy,
                        onClick = { performRestore(target, RestoreMode.Merge) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Merge — keep local data") }
                    FolioDestructiveTextButton(
                        text = "Replace — wipe this library first",
                        enabled = !busy,
                        onClick = { performRestore(target, RestoreMode.Replace) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(
                        enabled = !busy,
                        onClick = { restoreTarget = null },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel") }
                }
            }
        )
    }

    if (confirmResetDefaults) {
        AlertDialog(
            onDismissRequest = { confirmResetDefaults = false },
            title = { Text("Reset reading defaults?") },
            text = {
                Text(
                    "All reading defaults return to the factory values. Books that opened " +
                        "with their own settings keep them; nothing else changes."
                )
            },
            confirmButton = {
                FolioDestructiveTextButton(
                    text = "Reset defaults",
                    onClick = {
                        confirmResetDefaults = false
                        commitPrefs(ReadingPrefs())
                    },
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmResetDefaults = false }) { Text("Cancel") }
            },
        )
    }

    val corruptMessages = remember(readingSettingsCorrupt, appearanceCorrupt) {
        buildList {
            if (readingSettingsCorrupt) {
                add(
                    "Your saved reading settings are unreadable — defaults are shown. " +
                        "Nothing is lost: when you save new settings the unreadable file " +
                        "is preserved in library/recovery first."
                )
            }
            if (appearanceCorrupt) {
                add(
                    "Folio's appearance setting is unreadable — System is active. The " +
                        "unreadable file is preserved in library/recovery when you choose " +
                        "another option."
                )
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { (context as? ComponentActivity)?.finish() }) {
                        Icon(
                            FolioIcons.ArrowLeft,
                            contentDescription = "Go back",
                        )
                    }
                },
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
            if (corruptMessages.isNotEmpty()) {
                item("corrupt-banner") {
                    CorruptBanner(corruptMessages)
                }
            }

            item("appearance-header") { FolioSectionHeader(text = "Appearance") }
            item("appearance-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    AppThemePicker(
                        selected = appTheme,
                        onSelect = { key ->
                            if (key == appTheme) return@AppThemePicker
                            appTheme = key
                            appearanceCorrupt = false
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { appearanceStore.save(key) }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    showMessage(
                                        error.message?.takeIf { it.isNotBlank() }
                                            ?: "Couldn't save the appearance setting."
                                    )
                                }
                            }
                        },
                    )
                }
            }

            item("reading-header") { FolioSectionHeader(text = "Reading defaults") }
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
                        ReadingEditorSection("Preview") {
                            ReadingPagePreview(
                                prefs = prefs,
                                modifier = Modifier.padding(horizontal = FolioSpacing.x2),
                            )
                        }
                        ReadingPrefsEditor(
                            prefs = prefs,
                            onPrefsChange = ::commitPrefs,
                            // Preview updates the DISPLAY only; persistence
                            // happens on commit — no write storm (audit S1).
                            onPreview = { next -> if (prefsLoaded) prefs = next },
                        )
                        Text(
                            "Applies to new books. Changes made inside a book stay with " +
                                "that book until you reset it there.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(
                                start = FolioSpacing.x4,
                                end = FolioSpacing.x4,
                                top = FolioSpacing.x2,
                                bottom = FolioSpacing.x3,
                            ),
                        )
                        FolioSettingsRow(
                            title = "Reset reading defaults",
                            summary = "Factory values for new books. Per-book settings are untouched.",
                            onClick = { confirmResetDefaults = true },
                        )
                    }
                }
            }

            item("backup-header") { FolioSectionHeader(text = "Backup & restore") }
            item("backup-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    if (busy) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
                        ) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                busyLabel ?: "Working…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = FolioSpacing.x1),
                            )
                        }
                    }
                    FolioSettingsRow(
                        title = "Export backup",
                        summary = "Books, notes, progress, collections and reading settings — " +
                            "you pick the location.",
                        enabled = !busy,
                        onClick = { exportLauncher.launch(generateBackupFilename()) },
                    )
                    FolioSettingsRow(
                        title = "Restore backup",
                        summary = "Import a ZIP, then choose merge or replace.",
                        enabled = !busy,
                        onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
                    )
                }
            }

            item("storage-header") { FolioSectionHeader(text = "Storage & recovery") }
            item("storage-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    val current = storage
                    val valueOrState: String = when {
                        current != null -> ""
                        storageError -> "Unavailable"
                        else -> "Calculating…"
                    }
                    FolioSettingsRow(
                        title = "Library use",
                        value = current?.let { formatBytes(it.libraryBytes) } ?: valueOrState,
                        summary = "Books, notes, progress and search index.",
                    )
                    FolioSettingsRow(
                        title = "Folio data",
                        value = current?.let { formatBytes(it.appBytes) } ?: valueOrState,
                        summary = "Books, indexes, reader settings and local app data.",
                    )
                    FolioSettingsRow(
                        title = "Free space",
                        value = current?.let { formatBytes(it.freeBytes) } ?: valueOrState,
                    )
                    FolioSettingsRow(
                        title = "Recovery copies",
                        value = when (recoveryCopies) {
                            0 -> "None"
                            1 -> "1 copy"
                            else -> "$recoveryCopies copies"
                        },
                        summary = "First-aid copies Folio kept when a file was unreadable " +
                            "(library/recovery). They are never deleted by Folio.",
                    )
                    if (storageError) {
                        FolioSettingsRow(
                            title = "Retry storage check",
                            onClick = { storageReload++ },
                        )
                    }
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

/**
 * Persistent corrupt-state banners (S1 audit Phase 2): corruption is named
 * louder than an empty first-run state, with the recovery route spelled out.
 */
@Composable
private fun CorruptBanner(messages: List<String>) {
    Surface(
        shape = RoundedCornerShape(FolioRadii.card),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .padding(horizontal = FolioSpacing.x5)
            .fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x3)) {
            messages.forEachIndexed { index, message ->
                if (index > 0) {
                    Spacer(modifier = Modifier.padding(top = FolioSpacing.x1))
                }
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** App-interface appearance: System / Light / Dark (S1 audit Phase 2). */
@Composable
private fun AppThemePicker(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        AppAppearanceStore.KEY_SYSTEM to "System",
        AppAppearanceStore.KEY_LIGHT to "Light",
        AppAppearanceStore.KEY_DARK to "Dark",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioSpacing.x4, vertical = FolioSpacing.x2),
        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x2),
    ) {
        options.forEach { (key, label) ->
            ReadingSegment(
                label = label,
                selected = selected == key,
                onClick = { onSelect(key) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text(
        "Changes Folio's own screens. The reader page follows its own reading settings.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = FolioSpacing.x4,
            end = FolioSpacing.x4,
            bottom = FolioSpacing.x3,
        ),
    )
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

private fun recoveryCopyCount(context: Context): Int =
    File(context.filesDir, RECOVERY_DIR)
        .listFiles()?.count { it.isFile } ?: 0

/**
 * Streams a verified, PWA-compatible backup ZIP (entries added one file at a
 * time — the whole library never sits in memory) into the user-chosen
 * location. The archive is written to a temporary ".part" file in the cache
 * first and only copied to the target after the stream completes and flushes,
 * so an interrupted export never leaves a half-written document. Caller is on
 * Dispatchers.IO.
 */
private suspend fun exportBackup(context: Context, target: Uri): String {
    val store = AppFileStore(context.filesDir)
    val temp = File.createTempFile("folio-export-", ".part", context.cacheDir)
    try {
        FileOutputStream(temp).use { fos ->
            exportBackupZip(store, fos)
            fos.flush()
            fos.fd.sync()
        }
        val out = context.contentResolver.openOutputStream(target)
            ?: throw java.io.IOException("Could not write to the picked location")
        out.use { stream ->
            temp.inputStream().use { input -> input.copyTo(stream) }
            stream.flush()
        }
    } finally {
        temp.delete()
    }
    return "Backup exported."
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
        val warnings = summary.warnings + verification.issues.map { "${it.path} (${it.reason})" }
        val summaryText =
            "${summary.mode.replaceFirstChar { it.uppercase() }}: +${summary.newDocsAdded} new, " +
                "${summary.duplicatesSkipped} duplicates skipped, " +
                "${summary.metadataUpdated} updated, " +
                "${summary.annotationsMerged} annotations, " +
                "${summary.progressUpdated} progress, " +
                "${summary.collectionsMerged} collections"
        if (warnings.isEmpty()) {
            summaryText
        } else {
            "Restore completed with warnings — $summaryText. " +
                warnings.distinct().joinToString("; ")
        }
    }
}
