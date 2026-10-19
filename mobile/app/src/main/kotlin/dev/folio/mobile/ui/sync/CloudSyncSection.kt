package dev.folio.mobile.ui.sync

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.sync.CloudSyncScheduler
import dev.folio.mobile.core.sync.DriveAuth
import dev.folio.mobile.core.sync.DriveSyncProvider
import dev.folio.mobile.core.sync.SyncManager
import dev.folio.mobile.core.sync.SyncProgress
import dev.folio.mobile.core.sync.SyncResult
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Cloud sync" settings section.
 *
 * Folio stays offline-first: the device library is the source of truth and
 * the cloud is a *mirror* of it — the same file-by-file Drive folder the web
 * app uses, so the books are reachable from the Drive app, a browser, or any
 * other device with Folio signed in to the same account.
 *
 * One action does the work: "Sync now" pushes what is missing and pulls what
 * the other devices have.
 */
@Composable
fun CloudSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val auth = remember { DriveAuth(context) }
    val main = remember { Handler(Looper.getMainLooper()) }

    var accountName by remember { mutableStateOf(auth.selectedAccountName()) }
    var autoSync by remember { mutableStateOf(CloudSyncScheduler.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SyncProgress?>(null) }
    var status by remember { mutableStateOf("") }
    var showAccountPicker by remember { mutableStateOf(false) }

    fun syncManager(): SyncManager? {
        val credential = auth.credential() ?: return null
        val store = AppFileStore(context.filesDir)
        return SyncManager(
            store = store,
            library = LibraryManager(store),
            provider = DriveSyncProvider.create(credential),
            onProgress = { p -> main.post { progress = p } },
        )
    }

    fun startSync() {
        val manager = syncManager() ?: return
        busy = true
        progress = null
        status = ""
        scope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { manager.sync() } }
            busy = false
            progress = null
            outcome.fold(
                onSuccess = { status = describe(it) },
                onFailure = { error ->
                    // The first Drive call needs the user to grant the
                    // drive.file scope; the credential hands back a recovery
                    // intent for exactly that.
                    val recoverable = error as? UserRecoverableAuthIOException
                    if (recoverable != null) {
                        status = "Folio needs permission to use your Drive folder."
                        runCatching { context.startActivity(recoverable.intent) }
                    } else {
                        status = "Sync failed: ${error.message ?: error::class.simpleName}"
                    }
                },
            )
        }
    }

    FolioSettingsGroup {
        if (accountName == null) {
            FolioSettingsRow(
                title = "Connect Google Drive",
                summary = "Mirror your library to a Drive folder so you can pick " +
                    "it up on any device, and open the books anywhere.",
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
                            if (autoSync) {
                                autoSync = false
                                CloudSyncScheduler.setEnabled(context, false)
                            }
                            status = "Cloud sync disconnected. Your Drive copy is untouched."
                        },
                    ) { Text("Disconnect") }
                },
            )
            FolioSettingsRow(
                title = "Sync now",
                summary = "Uploads what is new here, downloads what other " +
                    "devices added. Merges notes and progress, never overwrites.",
                onClick = { if (!busy) startSync() },
            )
            FolioSettingsRow(
                title = "Auto-sync",
                summary = "Mirror to Drive every few hours, on Wi-Fi, while " +
                    "the battery is not low.",
                trailing = {
                    Switch(
                        checked = autoSync,
                        onCheckedChange = { enabled ->
                            autoSync = enabled
                            CloudSyncScheduler.setEnabled(context, enabled)
                            status = if (enabled) {
                                "Auto-sync on. Folio will mirror to Drive in the background."
                            } else {
                                "Auto-sync off."
                            }
                        },
                    )
                },
            )
        }
    }

    val current = progress
    if (current != null) {
        if (current.phase == "downloading" || current.phase == "uploading") {
            LinearProgressIndicator(
                progress = {
                    if (current.total > 0) current.current.toFloat() / current.total else 0f
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
            )
        }
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
}

/** One-line summary of a finished sync. */
private fun describe(result: SyncResult): String = buildString {
    append("Synced: ")
    append("${result.uploadedDocs} uploaded, ${result.downloadedDocs} downloaded")
    if (result.mergedUserFiles > 0) append(", ${result.mergedUserFiles} shared files merged")
    append(".")
}
