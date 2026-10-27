package dev.folio.mobile.core.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.store.AppFileStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background cloud sync.
 *
 * Opt-in: the user turns it on in Settings, and the worker then mirrors the
 * library to Drive every few hours, when the device is on an unmetered
 * network and the battery is not low. The library is untouched while the
 * device is offline — the app stays offline-first.
 */
class CloudSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = DriveAuth(applicationContext)
        val account = auth.savedAccountState()
        if (account is DriveAuth.SavedAccountState.NotConnected) {
            // Never report a silent success when auto-sync cannot possibly run.
            CloudSyncScheduler.markNeedsAttention(applicationContext, NEEDS_ACCOUNT)
            CloudSyncScheduler.setEnabled(applicationContext, false)
            return@withContext Result.failure()
        }
        if (account is DriveAuth.SavedAccountState.Missing) {
            CloudSyncScheduler.markNeedsAttention(applicationContext, NEEDS_ACCOUNT)
            CloudSyncScheduler.setEnabled(applicationContext, false)
            return@withContext Result.failure()
        }

        val credential = auth.credential()
        if (credential == null) {
            CloudSyncScheduler.markNeedsAttention(applicationContext, NEEDS_ACCOUNT)
            CloudSyncScheduler.setEnabled(applicationContext, false)
            return@withContext Result.failure()
        }

        try {
            val store = AppFileStore(applicationContext.filesDir)
            val manager = SyncManager(
                store = store,
                library = LibraryManager(store),
                provider = DriveSyncProvider.create(credential),
            )
            manager.sync()
            CloudSyncScheduler.clearAttention(applicationContext)
            Result.success()
        } catch (cancelled: CancellationException) {
            // WorkManager cancellation is not a transient sync failure.
            throw cancelled
        } catch (error: Throwable) {
            val failure = SyncFailureMapper.from(error)
            Log.w(
                TAG,
                "Background sync failed (${failure.kind}, ${error.javaClass.simpleName})",
            )
            when (failure.kind) {
                SyncFailureKind.AuthRequired,
                SyncFailureKind.PermissionDenied,
                SyncFailureKind.AccountMissing,
                SyncFailureKind.DataUnreadable,
                -> {
                    // A background worker cannot show a consent screen. Turn the
                    // schedule off and surface the reason when Settings opens.
                    CloudSyncScheduler.markNeedsAttention(applicationContext, failure.message)
                    CloudSyncScheduler.setEnabled(applicationContext, false)
                    Result.failure()
                }

                else ->
                    if (failure.retryable && runAttemptCount < 3) Result.retry()
                    else {
                        CloudSyncScheduler.markNeedsAttention(applicationContext, failure.message)
                        Result.failure()
                    }
            }
        }
    }

    private companion object {
        const val TAG = "FolioCloudSync"
        const val NEEDS_ACCOUNT = "Reconnect your Google account to resume auto-sync."
    }
}

/** Schedules (and cancels) the periodic background sync. */
object CloudSyncScheduler {

    /** Unique name, so re-scheduling replaces the previous period. */
    const val WORK_NAME = "folio-cloud-sync"

    /** How often the background sync runs. */
    const val INTERVAL_HOURS = 6L

    private const val PREFS = "folio-cloud-sync"
    private const val KEY_ENABLED = "auto-sync-enabled"
    private const val KEY_ATTENTION = "auto-sync-attention"

    /** Whether the user opted into background syncing. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** A user-facing reason auto-sync stopped, or null. */
    fun attentionMessage(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ATTENTION, null)

    /** Turns background syncing on or off and (re)schedules the worker. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) schedule(context) else cancel(context)
    }

    /** Records why auto-sync cannot continue until the user intervenes. */
    fun markNeedsAttention(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ATTENTION, message)
            .apply()
    }

    /** Clears a previous attention message after a successful sync/setup. */
    fun clearAttention(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_ATTENTION)
            .apply()
    }

    /** Schedules the periodic worker (unmetered network, battery not low). */
    fun schedule(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
            INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Cancels the periodic worker. */
    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
