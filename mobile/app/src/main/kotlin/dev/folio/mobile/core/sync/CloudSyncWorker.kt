package dev.folio.mobile.core.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.folio.mobile.core.library.LibraryManager
import dev.folio.mobile.core.store.AppFileStore
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
        val credential = auth.credential() ?: return@withContext Result.success()
        try {
            val store = AppFileStore(applicationContext.filesDir)
            val manager = SyncManager(
                store = store,
                library = LibraryManager(store),
                provider = DriveSyncProvider.create(credential),
            )
            manager.sync()
            Result.success()
        } catch (t: Throwable) {
            // Retry on transient failures (network, rate limits); give up on
            // anything that will not get better by trying again soon.
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
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

    /** Whether the user opted into background syncing. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    /** Turns background syncing on or off and (re)schedules the worker. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        if (enabled) schedule(context) else cancel(context)
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
