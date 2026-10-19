package dev.folio.mobile.ui.sync

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.folio.mobile.core.backup.RestoreMode
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.sync.CloudSyncOutcome
import dev.folio.mobile.core.sync.DriveAuth
import dev.folio.mobile.core.sync.DriveCloudSyncStore
import dev.folio.mobile.core.sync.RemoteBackupInfo
import dev.folio.mobile.core.sync.SyncCoordinator
import dev.folio.mobile.ui.components.FolioDestructiveTextButton
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Cloud sync" settings section.
 *
 * Folio stays offline-first: the device library is the source of truth and
 * the cloud holds exactly one backup snapshot, mirrored in the user's own
 * hidden Google Drive folder. Syncing therefore reuses the backup contract
 * the app already has — the same ZIP the "Export backup" row produces.
 */
@Composable
fun CloudSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { DriveAuth(context) }
    val main = remember { Handler(Looper.getMainLooper()) }

    var accountName by remember { mutableStateOf(auth.selectedAccountName()) }
    var remote by remember { mutableStateOf<RemoteBackupInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var status by remember { mutableStateOf("") }
    var showAccountPicker by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<RestoreMode?>(null) }

    fun coordinator(): SyncCoordinator? {
        val credential = auth.credential() ?: return null
        return SyncCoordinator(
            store = DriveCloudSyncStore.create(credential),
            library = AppFileStore(context.filesDir),
            stagingDir = context.cacheDir,
        )
    }

    // Refresh the remote snapshot info whenever the connected account changes.
    LaunchedEffect(accountName) {
        if (accountName == null) {
            remote = null
            return@LaunchedEffect
        }
        val coordinator = coordinator() ?: return@LaunchedEffect
        remote = withContext(Dispatchers.IO) { coordinator.remoteInfo() }
    }

    fun startUpload() {
        val coordinator = coordinator() ?: return
        busy = true
        progress = 0f
        status = "Packing your library…"
        scope.launch {
            val outcome = coordinator.upload(
                onStage = { current, total, _ ->
                    main.post { status = "Packing your library… $current of $total files" }
                },
                onProgress = { uploaded, total ->
                    main.post {
                        progress = if (total > 0) uploaded.toFloat() / total else 0f
                    }
                },
            )
            busy = false
            progress = null
            status = describe(outcome)
            if (outcome is CloudSyncOutcome.Uploaded) remote = outcome.info
        }
    }

    fun startRestore(mode: RestoreMode) {
        val coordinator = coordinator() ?: return
        busy = true
        progress = null
        status = "Restoring from Drive…"
        scope.launch {
            val outcome = coordinator.downloadAndRestore(mode)
            busy = false
            status = describe(outcome)
        }
    }

    FolioSettingsGroup {
        if (accountName == null) {
            FolioSettingsRow(
                title = "Connect Google Drive",
                summary = "Keep a copy of your library in your own Google Drive " +
                    "so you can pick it up on any device.",
                onClick = { showAccountPicker = true },
            )
        } else {
            FolioSettingsRow(
                title = "Google account",
                summary = accountName,
                trailing = {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            auth.disconnect()
                            accountName = null
                            remote = null
                            status = "Cloud sync disconnected. The cloud copy is untouched."
                        },
                    ) { Text("Disconnect") }
                },
            )
            FolioSettingsRow(
                title = "Back up to Drive",
                summary = remoteSummary(remote),
                onClick = { if (!busy) startUpload() },
            )
            FolioSettingsRow(
                title = "Restore from Drive",
                summary = "Pull the cloud copy into this device. You choose merge or replace.",
                onClick = { if (!busy) pendingRestore = RestoreMode.Merge },
            )
        }
    }

    if (progress != null) {
        LinearProgressIndicator(
            progress = { progress ?: 0f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
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

    if (showAccountPicker) {
        val accounts = auth.googleAccounts()
        AlertDialog(
            onDismissRequest = { showAccountPicker = false },
            title = { Text("Choose a Google account") },
            text = {
                Column {
                    if (accounts.isEmpty()) {
                        Text(
                            "No Google account found on this device. Add one in " +
                                "Settings, then come back to enable cloud sync."
                        )
                    } else {
                        accounts.forEach { account ->
                            TextButton(
                                onClick = {
                                    auth.connect(account)
                                    accountName = account.name
                                    showAccountPicker = false
                                },
                            ) { Text(account.name) }
                        }
                    }
                }
            },
            confirmButton = {
                if (accounts.isEmpty()) {
                    TextButton(onClick = {
                        showAccountPicker = false
                        runCatching { context.startActivity(auth.addAccountIntent()) }
                    }) { Text("Add account") }
                } else {
                    TextButton(onClick = { showAccountPicker = false }) { Text("Cancel") }
                }
            },
        )
    }

    pendingRestore?.let { mode ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore from Drive") },
            text = {
                Text(
                    "Merge: add what's missing, keep everything local, newer " +
                        "annotations/progress win. Replace: wipe this library " +
                        "first and restore exactly the cloud copy."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingRestore = null
                        startRestore(RestoreMode.Merge)
                    },
                ) { Text("Merge") }
                FolioDestructiveTextButton(
                    text = "Replace",
                    enabled = !busy,
                    onClick = {
                        pendingRestore = null
                        startRestore(RestoreMode.Replace)
                    },
                )
                TextButton(enabled = !busy, onClick = { pendingRestore = null }) { Text("Cancel") }
            },
        )
    }
}

/** One-line status for a finished sync. */
private fun describe(outcome: CloudSyncOutcome): String = when (outcome) {
    is CloudSyncOutcome.Uploaded ->
        "Backed up to Drive (${formatBytes(outcome.info.sizeBytes)})."
    is CloudSyncOutcome.Restored -> {
        val s = outcome.summary
        val warnings = if (outcome.verification.valid) {
            ""
        } else {
            " Restore completed with warnings: " +
                outcome.verification.issues.joinToString("; ") { "${it.path} (${it.reason})" }
        }
        "${s.mode.replaceFirstChar { it.uppercase() }}: +${s.newDocsAdded} new, " +
            "${s.duplicatesSkipped} duplicates skipped, ${s.metadataUpdated} updated, " +
            "${s.annotationsMerged} annotations, ${s.progressUpdated} progress, " +
            "${s.collectionsMerged} collections.$warnings"
    }
    is CloudSyncOutcome.NoRemoteBackup ->
        "No cloud backup yet. Use “Back up to Drive” to create one."
    is CloudSyncOutcome.Failed ->
        "Cloud sync failed: ${outcome.error.message ?: outcome.error::class.simpleName}"
}

/** Summary line for the "Back up to Drive" row. */
private fun remoteSummary(remote: RemoteBackupInfo?): String = when {
    remote == null -> "No cloud copy yet. Back up to create one."
    else -> "Cloud copy from ${formatTimestamp(remote.modifiedTime)} " +
        "(${formatBytes(remote.sizeBytes)}). Backing up again replaces it."
}

private val TIMESTAMP_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.getDefault())

private fun formatTimestamp(iso: String?): String {
    if (iso.isNullOrBlank()) return "an earlier sync"
    return runCatching {
        TIMESTAMP_FORMAT.format(
            Instant.parse(iso).atZone(ZoneId.systemDefault())
        )
    }.getOrElse { "an earlier sync" }
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024 * 1024 -> "${(bytes / 1024.0).toInt()} KB"
    else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
}
