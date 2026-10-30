package dev.folio.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.folio.mobile.core.backup.BackupRestore
import dev.folio.mobile.core.backup.exportBackupZip
import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.backup.generateBackupFilename
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.library.ReadResult
import dev.folio.mobile.core.store.AppAppearanceStore
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.directoryBytes
import dev.folio.mobile.core.store.formatBytes
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioSectionHeader
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.components.ReadingSegment
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioAppTheme
import dev.folio.mobile.ui.theme.FolioMobileTheme
import dev.folio.mobile.ui.theme.FolioRadii
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // M-26: seed the process-wide app theme holder instead of a local
        // snapshot. The blocking first read keeps the first frame on the
        // stored choice; later changes to the holder re-theme this screen
        // (and the Library beneath it) live.
        FolioAppTheme.ensure(AppFileStore(filesDir))
        setContent {
            FolioMobileTheme(
                darkTheme = AppAppearanceStore.isDark(
                    FolioAppTheme.key.value,
                    isSystemInDarkTheme(),
                ),
            ) {
                SettingsScreen()
            }
        }
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, SettingsActivity::class.java)
    }
}

/**
 * Settings (M-26 redesign): a small app-management screen with exactly three
 * groups — Appearance (System/Light/Dark), Your library (backup export,
 * merge/replace restore, library storage), About (version).
 *
 * Reader configuration deliberately does NOT live here: per-book controls are
 * the reader's appearance sheet, and global reading defaults keep their
 * `PrefsStore` persistence + backup path untouched — they are now seeded from
 * the reader's "Set as default for future books" action instead of this
 * screen's editor. Diagnostic rows (app-wide data, free space, recovery-copy
 * count) went with it; the recovery copies themselves are untouched.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val appearanceStore = remember { AppAppearanceStore(AppFileStore(context.filesDir)) }

    /**
     * Living app-UI theme key, read straight from the process-wide holder so
     * writing it re-themes this screen (and any screen under it) immediately.
     */
    val appTheme = FolioAppTheme.key.value

    /** Honest corrupt-state flag for the app-appearance file. */
    var appearanceCorrupt by remember { mutableStateOf(false) }

    var busy by remember { mutableStateOf(false) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var libraryBytes by remember { mutableStateOf<Long?>(null) }
    var storageError by remember { mutableStateOf(false) }
    var storageReload by remember { mutableStateOf(0) }

    /** Picked backup URI awaiting the merge/replace mode choice. */
    var restoreTarget by remember { mutableStateOf<Uri?>(null) }

    fun showMessage(message: String) {
        scope.launch { snackbar.showSnackbar(message) }
    }

    fun selectAppTheme(key: String) {
        if (key == appTheme) return
        // UI first (holder is snapshot state — every attached screen re-themes
        // now), then persist async. A failed save leaves the file untouched,
        // so the next process start still reads the last good choice.
        FolioAppTheme.set(key)
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
    }

    LaunchedEffect(Unit) {
        // The holder already resolved corrupt → System for the FIRST frame at
        // onCreate; this read only surfaces the honest corrupt state so the
        // user knows their stored file needs re-saving, not quiet factory
        // defaults.
        try {
            when (withContext(Dispatchers.IO) { appearanceStore.read() }) {
                is ReadResult.Loaded -> Unit
                ReadResult.Missing -> Unit
                is ReadResult.Corrupt -> appearanceCorrupt = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            showMessage("Folio's appearance setting could not be read.")
        }
    }

    LaunchedEffect(storageReload) {
        storageError = false
        libraryBytes = try {
            withContext(Dispatchers.IO) {
                directoryBytes(File(context.filesDir, "library"))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            storageError = true
            null
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
                .padding(padding),
            contentPadding = PaddingValues(bottom = FolioSpacing.x8),
        ) {
            item("page-subtitle") {
                Text(
                    "A few essentials to make Folio yours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = FolioSpacing.x5, vertical = FolioSpacing.x1),
                )
            }

            if (appearanceCorrupt) {
                item("appearance-corrupt-banner") {
                    CorruptBanner(
                        listOf(
                            "Folio's appearance setting is unreadable — System is active. The " +
                                "unreadable file is preserved in library/recovery when you choose " +
                                "another option."
                        )
                    )
                }
            }

            item("appearance-header") { FolioSectionHeader(text = "Appearance") }
            item("appearance-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    AppThemePicker(
                        selected = appTheme,
                        onSelect = ::selectAppTheme,
                    )
                }
            }

            item("library-header") { FolioSectionHeader(text = "Your library") }
            item("library-group") {
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
                    val currentBytes = libraryBytes
                    FolioSettingsRow(
                        title = "Library storage",
                        value = when {
                            currentBytes != null -> formatBytes(currentBytes)
                            storageError -> "Unavailable"
                            else -> "Calculating…"
                        },
                        summary = "Books, notes, progress and search index.",
                        // Failed checks retry in place — a transient read
                        // failure must not look permanent.
                        onClick = if (storageError) ({ storageReload++ }) else null,
                        trailing = if (storageError) ({
                            Icon(
                                FolioIcons.RefreshCcw,
                                contentDescription = "Retry the storage check",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }) else null,
                    )
                    FolioSettingsRow(
                        title = "Export backup",
                        summary = "Save your library as a ZIP archive.",
                        enabled = !busy,
                        onClick = { exportLauncher.launch(generateBackupFilename()) },
                    )
                    FolioSettingsRow(
                        title = "Restore backup",
                        summary = "Restore your books and saved data.",
                        enabled = !busy,
                        onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
                    )
                }
            }

            item("about-header") { FolioSectionHeader(text = "About") }
            item("about-group") {
                FolioSettingsGroup(modifier = Modifier.padding(horizontal = FolioSpacing.x5)) {
                    FolioSettingsRow(
                        title = "Folio",
                        summary = "Your personal reading space.",
                        value = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    )
                }
            }
        }
    }
}

/**
 * Persistent corrupt banner (S1 audit Phase 2, explicitly kept by the M-26
 * redesign): corruption is named louder than a silent System reset, with the
 * recovery route spelled out. Reader-settings corruption no longer surfaces
 * here — the reader owns that warning where the data is actually used.
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

/** App-interface appearance: System / Light / Dark (shared holder-backed). */
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
    Text(
        "App theme",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = FolioSpacing.x4, end = FolioSpacing.x4, top = FolioSpacing.x3),
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
