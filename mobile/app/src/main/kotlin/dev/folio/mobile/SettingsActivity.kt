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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import dev.folio.mobile.core.model.ReadingPrefs
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.store.PrefsStore
import dev.folio.mobile.core.store.directoryBytes
import dev.folio.mobile.ui.theme.FolioMobileTheme
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
                    TextButton(
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
                    ) { Text("Replace") }
                    TextButton(enabled = !busy, onClick = { restoreTarget = null }) { Text("Cancel") }
                }
            }
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("light", "warm", "dark", "true-black", "system").forEach { key ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val updated = prefs.copy(theme = key)
                                prefs = updated
                                withContext(Dispatchers.IO) { prefsStore.saveReadingPrefs(updated) }
                            }
                        }
                    ) { Text(if (prefs.theme == key) "• $key" else key) }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text(storageInfo(context), style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Text("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodyMedium)

            Spacer(Modifier.height(8.dp))
            Text("Backup", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy,
                    onClick = {
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
                ) { Text("Export backup") }
                OutlinedButton(
                    enabled = !busy,
                    onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) }
                ) { Text("Restore backup") }
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
            if (busy) {
                Text("Working…", style = MaterialTheme.typography.bodySmall)
            }
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
