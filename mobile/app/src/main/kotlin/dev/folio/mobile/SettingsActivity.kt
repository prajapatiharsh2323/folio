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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioFilledButton
import dev.folio.mobile.ui.components.FolioOutlinedButton
import dev.folio.mobile.ui.components.FolioSectionHeader
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.icons.FolioIcons
import dev.folio.mobile.ui.theme.FolioMobileTheme
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.Dispatchers
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

/**
 * Display labels for the stored theme keys (serialized keys are unchanged:
 * "light"/"warm"/"dark"/"true-black"/"system" — only labels differ).
 * "warm" displays as "Paper" per the design system naming pass.
 */
private val THEME_LABELS = listOf(
    "light" to "Light",
    "warm" to "Paper",
    "dark" to "Dark",
    "true-black" to "True black",
    "system" to "System",
)

private val THEME_SWATCH_ARGB = mapOf(
    "light" to 0xFFFAFAF7,
    "warm" to 0xFFF4ECD8,
    "dark" to 0xFF121214,
    "true-black" to 0xFF000000,
    "system" to 0xFFF7F6F1,
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsStore = remember { PrefsStore(AppFileStore(context.filesDir)) }
    var prefs by remember { mutableStateOf(ReadingPrefs()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    /** Picked backup URI awaiting the merge/replace mode choice. */
    var restoreTarget by remember { mutableStateOf<Uri?>(null) }

    LaunchedEffect(Unit) {
        prefs = withContext(Dispatchers.IO) { prefsStore.loadReadingPrefs() }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> restoreTarget = uri }

    // Restore mode chooser (spec §8: merge keeps local data, replace wipes).
    // Merge is the plain option; Replace is styled destructive because it
    // erases the existing library.
    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text("Restore backup") },
            text = {
                Text(
                    "Merge: add what's missing, keep everything local, newer " +
                        "annotations/progress win. Replace: wipe this library " +
                        "first and restore exactly the backup."
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            restoreTarget = null
                            scope.launch {
                                busy = true
                                status = try {
                                    withContext(Dispatchers.IO) { restoreBackup(context, target, RestoreMode.Merge) }
                                } catch (t: Throwable) {
                                    "Restore failed: ${t.message}"
                                }
                                busy = false
                            }
                        }
                    ) { Text("Merge") }
                    FolioDestructiveTextButton(
                        text = "Replace",
                        enabled = !busy,
                        onClick = {
                            restoreTarget = null
                            scope.launch {
                                busy = true
                                status = try {
                                    withContext(Dispatchers.IO) { restoreBackup(context, target, RestoreMode.Replace) }
                                } catch (t: Throwable) {
                                    "Restore failed: ${t.message}"
                                }
                                busy = false
                            }
                        }
                    )
                    TextButton(enabled = !busy, onClick = { restoreTarget = null }) { Text("Cancel") }
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            FolioSectionHeader(text = "Reading")

            FolioSettingsGroup {
                Column(modifier = Modifier.padding(horizontal = FolioSpacing.x5, vertical = FolioSpacing.x2)) {
                    Text(
                        "Default reading appearance",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = FolioSpacing.x2),
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(FolioSpacing.x3),
                        modifier = Modifier.padding(vertical = FolioSpacing.x1),
                    ) {
                        THEME_LABELS.forEach { (key, label) ->
                            val selected = prefs.theme == key
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Surface(
                                    shape = CircleShape,
                                    color = Color(THEME_SWATCH_ARGB.getValue(key)),
                                    border = BorderStroke(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.outline
                                        },
                                    ),
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clickable {
                                            scope.launch {
                                                val updated = prefs.copy(theme = key)
                                                prefs = updated
                                                withContext(Dispatchers.IO) { prefsStore.saveReadingPrefs(updated) }
                                            }
                                        },
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (selected) {
                                            androidx.compose.material3.Icon(
                                                FolioIcons.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.padding(top = FolioSpacing.x1),
                                )
                            }
                        }
                    }
                }
                FolioSettingsRow(
                    title = "Applies to new books",
                    summary = "Books where you change appearance in the reader " +
                        "keep their own settings instead.",
                )
            }

            FolioSectionHeader(text = "Backup")

            FolioSettingsGroup {
                FolioSettingsRow(
                    title = "Export backup",
                    summary = "A ZIP of your library, saved to Folio's backups folder.",
                    onClick = {
                        if (!busy) {
                            scope.launch {
                                busy = true
                                status = try {
                                    withContext(Dispatchers.IO) { exportBackup(context) }
                                } catch (t: Throwable) {
                                    "Backup failed: ${t.message}"
                                }
                                busy = false
                            }
                        }
                    },
                )
                FolioSettingsRow(
                    title = "Restore backup",
                    summary = "Import an exported ZIP. You choose merge or replace.",
                    onClick = {
                        if (!busy) {
                            restoreLauncher.launch(arrayOf("application/zip", "*/*"))
                        }
                    },
                )
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
                )
            }
            if (busy) {
                Text(
                    "Working…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
                )
            }

            FolioSectionHeader(text = "Storage")

            FolioSettingsGroup {
                FolioSettingsRow(
                    title = "Library size",
                    summary = storageInfo(context),
                )
            }

            FolioSectionHeader(text = "About")

            FolioSettingsGroup {
                FolioSettingsRow(
                    title = "App version",
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
            }

            Spacer(Modifier.height(FolioSpacing.x8))
        }
    }
}

/**
 * Audit EPUB-003: the stat was StatFs on filesDir labelled "Used" — that is
 * the whole app partition (dozens of GB), functionally static device info.
 * The actual signal for "how big is my library" is folio's own payload:
 * byte sum of everything under filesDir (schema-1 library + prefs + backups).
 * Free space on the partition stays as secondary info.
 */
private fun storageInfo(context: Context): String {
    val dir = context.filesDir
    val folioBytes = directoryBytes(dir)
    val free = StatFs(dir.absolutePath).availableBytes
    return "Folio uses ${formatMb(folioBytes)} — ${formatMb(free)} free on device"
}

private fun formatMb(bytes: Long): String =
    String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))

/**
 * Streams a verified, PWA-compatible backup ZIP (entries added one file at a
 *  time — the whole library never sits in memory). Suspend + inline zip
 *  hashing below — the caller is already on Dispatchers.IO (audit EPUB-006:
 *  no main-thread runBlocking).
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
 * Verified restore via BackupRestore (spec §8 merge/replace rules ported from
 * packages/core/src/backup/import-zip.ts). The archive is staged to a temp
 * file and streamed per entry — the entire library never sits in RAM
 * (audit EPUB-007), and the caller is already on Dispatchers.IO (EPUB-006).
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
