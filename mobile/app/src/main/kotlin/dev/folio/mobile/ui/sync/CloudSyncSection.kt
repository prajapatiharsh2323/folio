package dev.folio.mobile.ui.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.store.AppFileStore
import dev.folio.mobile.core.sync.CloudSyncScheduler
import dev.folio.mobile.core.sync.DriveAuth
import dev.folio.mobile.core.sync.DriveSyncProvider
import dev.folio.mobile.core.sync.SyncFailureKind
import dev.folio.mobile.core.sync.SyncFailureMapper
import dev.folio.mobile.core.sync.SyncManager
import dev.folio.mobile.core.sync.SyncProgress
import dev.folio.mobile.core.sync.SyncResult
import dev.folio.mobile.ui.components.FolioSettingsGroup
import dev.folio.mobile.ui.components.FolioSettingsRow
import dev.folio.mobile.ui.theme.FolioSpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * "Cloud sync" settings section.
 *
 * Folio stays offline-first: the device library is the source of truth and
 * the cloud is a mirror. The mirror is a visible "Folio Library" folder in the
 * user's own Drive, created by this OAuth client; another install of this app
 * signed into the same account can read it. `drive.file` does not give a
 * different Folio client (for example the PWA) access to the same files.
 *
 * Selecting a Google account is deliberately separate from granting Drive
 * access. The consent screen is opened on the first sync, and the UI never
 * calls the setup "synced" until a sync actually succeeds.
 */
@Composable
fun CloudSyncSection() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val auth = remember { DriveAuth(context) }
    val main = remember { Handler(Looper.getMainLooper()) }

    var accountState by remember { mutableStateOf(auth.savedAccountState()) }
    var autoSync by remember { mutableStateOf(CloudSyncScheduler.isEnabled(context)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<SyncProgress?>(null) }
    var state by remember { mutableStateOf(SyncUiState.Idle) }
    var message by remember { mutableStateOf<String?>(null) }
    val startSyncRef = remember { mutableStateOf<(() -> Unit)?>(null) }

    fun refreshAccountState() {
        accountState = auth.savedAccountState()
        if (accountState is DriveAuth.SavedAccountState.NotConnected ||
            accountState is DriveAuth.SavedAccountState.Missing
        ) {
            if (autoSync) {
                autoSync = false
                CloudSyncScheduler.setEnabled(context, false)
            }
            CloudSyncScheduler.cancel(context)
        }
    }

    LaunchedEffect(Unit) {
        refreshAccountState()
        CloudSyncScheduler.attentionMessage(context)?.let {
            message = it
            state = SyncUiState.Error
        }
    }

    // The user can remove the saved account in Android Settings while Folio is
    // in the background. Revalidate whenever Settings becomes visible again.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshAccountState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun accountName(): String? = when (val current = accountState) {
        is DriveAuth.SavedAccountState.Connected -> current.name
        is DriveAuth.SavedAccountState.Missing -> current.name
        is DriveAuth.SavedAccountState.Unverified -> current.name
        DriveAuth.SavedAccountState.NotConnected -> null
    }

    val accountChooser = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            message = "No account selected. Nothing was changed."
            state = SyncUiState.Idle
            return@rememberLauncherForActivityResult
        }
        val data = result.data
        val name = data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        val type = data?.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE)
            ?: DriveAuth.GOOGLE_ACCOUNT_TYPE
        if (name.isNullOrBlank()) {
            message = "The account picker didn't return an account. Nothing was changed."
            state = SyncUiState.Error
            return@rememberLauncherForActivityResult
        }
        if (type != DriveAuth.GOOGLE_ACCOUNT_TYPE) {
            message = "Choose a Google account to use Drive sync."
            state = SyncUiState.Error
            return@rememberLauncherForActivityResult
        }

        val changed = auth.connect(Account(name, type))
        if (changed) {
            // Drive ids from the previous account must not leak into this one.
            scope.launch(Dispatchers.IO) {
                File(context.filesDir, SyncManager.SYNC_STATE_PATH).delete()
            }
            autoSync = false
            CloudSyncScheduler.setEnabled(context, false)
        }
        CloudSyncScheduler.clearAttention(context)
        accountState = DriveAuth.SavedAccountState.Connected(name)
        state = SyncUiState.Idle
        message = "Google account selected. Drive access is requested on the first sync."
    }

    fun openAccountChooser() {
        if (busy) return
        try {
            accountChooser.launch(auth.chooseAccountIntent())
        } catch (e: ActivityNotFoundException) {
            message = "The Google account picker isn't available on this device."
            state = SyncUiState.Error
            runCatching { context.startActivity(auth.addAccountIntent()) }
                .onFailure {
                    message = "Add a Google account in Android Settings, then try again."
                }
        } catch (e: Exception) {
            message = "Couldn't open the Google account picker. Try again."
            state = SyncUiState.Error
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startSyncRef.value?.invoke()
        } else {
            busy = false
            progress = null
            state = SyncUiState.NeedsPermission
            message = "Drive access wasn't granted. Sync is paused; you can retry when ready."
            if (autoSync) {
                autoSync = false
                CloudSyncScheduler.setEnabled(context, false)
            }
        }
    }

    fun stateLabel(): String = when (state) {
        SyncUiState.Idle -> if (accountName() == null) "Not connected" else "Ready to sync"
        SyncUiState.Syncing -> "Syncing"
        SyncUiState.Synced -> "Synced"
        SyncUiState.NeedsPermission -> "Needs Drive permission"
        SyncUiState.Offline -> "Offline"
        SyncUiState.Error -> "Needs attention"
    }

    fun startSync() {
        if (busy) return
        val current = accountState
        if (current is DriveAuth.SavedAccountState.NotConnected) {
            message = "Choose a Google account first."
            state = SyncUiState.Idle
            return
        }
        if (current is DriveAuth.SavedAccountState.Missing) {
            message = "That Google account is no longer on this device. Reconnect it."
            state = SyncUiState.Error
            autoSync = false
            CloudSyncScheduler.setEnabled(context, false)
            return
        }
        val credential = auth.credential()
        if (credential == null) {
            message = "Choose a Google account first."
            state = SyncUiState.Idle
            return
        }

        val manager = SyncManager(
            store = AppFileStore(context.filesDir),
            library = LibraryManager(AppFileStore(context.filesDir)),
            provider = DriveSyncProvider.create(credential),
            onProgress = { p -> main.post { progress = p } },
        )
        busy = true
        progress = null
        state = SyncUiState.Syncing
        message = null
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { manager.sync() }
                busy = false
                progress = null
                state = SyncUiState.Synced
                message = describe(result)
                CloudSyncScheduler.clearAttention(context)
                refreshAccountState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: UserRecoverableAuthIOException) {
                busy = false
                progress = null
                state = SyncUiState.NeedsPermission
                message = "Folio needs permission to use your Drive folder. Grant access to continue."
                try {
                    consentLauncher.launch(error.intent)
                } catch (e: ActivityNotFoundException) {
                    message = "Drive permission can't be opened on this device. Try again later."
                }
            } catch (error: Throwable) {
                busy = false
                progress = null
                val failure = SyncFailureMapper.from(error)
                state = when (failure.kind) {
                    SyncFailureKind.Offline -> SyncUiState.Offline
                    SyncFailureKind.AuthRequired,
                    SyncFailureKind.PermissionDenied,
                    SyncFailureKind.AccountMissing,
                    -> SyncUiState.NeedsPermission
                    else -> SyncUiState.Error
                }
                message = failure.message
                if (failure.kind == SyncFailureKind.AccountMissing ||
                    failure.kind == SyncFailureKind.PermissionDenied
                ) {
                    autoSync = false
                    CloudSyncScheduler.setEnabled(context, false)
                }
            }
        }
    }

    androidx.compose.runtime.SideEffect { startSyncRef.value = { startSync() } }

    val name = accountName()
    FolioSettingsGroup {
        if (name == null) {
            FolioSettingsRow(
                title = "Connect Google Drive",
                summary = "Mirror this library to a visible Folio Library folder. " +
                    "Other Folio Android devices can read it.",
                onClick = { openAccountChooser() },
            )
        } else {
            FolioSettingsRow(
                title = name,
                summary = stateLabel(),
                trailing = {
                    TextButton(enabled = !busy, onClick = { openAccountChooser() }) {
                        Text("Change")
                    }
                },
            )
            FolioSettingsRow(
                title = "Sync now",
                summary = "Merge new books, notes and reading progress.",
                onClick = { startSync() },
            )
            FolioSettingsRow(
                title = "Auto-sync",
                summary = "Every 6 hours on unmetered networks, when battery isn't low.",
                trailing = {
                    Switch(
                        checked = autoSync,
                        enabled = !busy && accountState !is DriveAuth.SavedAccountState.Missing,
                        onCheckedChange = { enabled ->
                            autoSync = enabled
                            CloudSyncScheduler.setEnabled(context, enabled)
                            if (enabled) CloudSyncScheduler.clearAttention(context)
                            state = SyncUiState.Idle
                            message = if (enabled) {
                                "Auto-sync on. Background sync will run when your device allows it."
                            } else {
                                "Auto-sync off."
                            }
                        },
                    )
                },
            )
            FolioSettingsRow(
                title = "Disconnect account",
                summary = "Your Drive copy stays untouched.",
                onClick = {
                    if (busy) return@FolioSettingsRow
                    auth.disconnect()
                    accountState = DriveAuth.SavedAccountState.NotConnected
                    autoSync = false
                    CloudSyncScheduler.setEnabled(context, false)
                    CloudSyncScheduler.clearAttention(context)
                    state = SyncUiState.Idle
                    message = "Cloud sync disconnected."
                },
            )
        }
    }

    if (busy) {
        val current = progress
        if (current != null && current.total > 0 &&
            (current.phase == "downloading" || current.phase == "uploading")
        ) {
            LinearProgressIndicator(
                progress = { current.current.toFloat() / current.total },
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

    message?.let { text ->
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state == SyncUiState.Error || state == SyncUiState.NeedsPermission) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = FolioSpacing.x6, vertical = FolioSpacing.x2),
        )
    }
}

private enum class SyncUiState {
    Idle,
    Syncing,
    Synced,
    NeedsPermission,
    Offline,
    Error,
}

/** One-line summary of a finished sync; only called after persistence. */
private fun describe(result: SyncResult): String = buildString {
    append("Synced: ")
    append("${result.uploadedDocs} uploaded, ${result.downloadedDocs} downloaded")
    if (result.mergedUserFiles > 0) append(", ${result.mergedUserFiles} shared files merged")
    append(".")
}
