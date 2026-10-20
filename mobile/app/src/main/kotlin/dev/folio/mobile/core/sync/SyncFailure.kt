package dev.folio.mobile.core.sync

import com.google.android.gms.auth.GoogleAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** A user-safe classification of a failed sync. */
enum class SyncFailureKind {
    /** Drive scope consent is needed (or was revoked). */
    AuthRequired,
    /** The account exists but Drive access was denied. */
    PermissionDenied,
    /** The saved account is no longer available to the app. */
    AccountMissing,
    /** No usable network connection. */
    Offline,
    /** Google Drive asked us to slow down. */
    RateLimited,
    /** A temporary Drive/server failure. */
    Server,
    /** A local storage failure. */
    Storage,
    Unknown,
}

data class SyncFailure(
    val kind: SyncFailureKind,
    val message: String,
    val retryable: Boolean,
)

/**
 * Maps provider exceptions to concise UI copy. Callers must rethrow
 * [kotlinx.coroutines.CancellationException] before calling this; a cancelled
 * sync is not a failure and must never be shown as one.
 *
 * Technical details belong in debug logs, never in the returned message.
 */
object SyncFailureMapper {

    fun from(error: Throwable): SyncFailure = when {
        error is UserRecoverableAuthIOException -> SyncFailure(
            SyncFailureKind.AuthRequired,
            "Folio needs permission to use Google Drive. Grant access and try again.",
            retryable = false,
        )

        error is GoogleJsonResponseException -> fromHttp(error)

        error is SecurityException || error is GoogleAuthException -> SyncFailure(
            SyncFailureKind.AccountMissing,
            "That Google account is no longer available. Reconnect it and try again.",
            retryable = false,
        )

        error is UnknownHostException ||
            error is ConnectException ||
            error is SocketTimeoutException ||
            error is SSLException -> offline()

        error is FileNotFoundException -> SyncFailure(
            SyncFailureKind.Storage,
            "A file needed for sync could not be read. Check the library and try again.",
            retryable = false,
        )

        error is IOException -> offline()

        error is IllegalStateException && error.message?.contains("account", true) == true ->
            SyncFailure(
                SyncFailureKind.AccountMissing,
                "That Google account is no longer available. Reconnect it and try again.",
                retryable = false,
            )

        else -> SyncFailure(
            SyncFailureKind.Unknown,
            "Sync didn't finish. Check your connection and try again.",
            retryable = true,
        )
    }

    private fun fromHttp(error: GoogleJsonResponseException): SyncFailure {
        val status = error.statusCode
        val detail = error.details?.message.orEmpty()
        return when {
            status == 401 -> SyncFailure(
                SyncFailureKind.AuthRequired,
                "Folio needs permission to use Google Drive. Grant access and try again.",
                retryable = false,
            )

            status == 403 && detail.contains("rateLimitExceeded", true) -> rateLimited()

            status == 403 -> SyncFailure(
                SyncFailureKind.PermissionDenied,
                "Google Drive denied access. Reconnect the account or grant access, then try again.",
                retryable = false,
            )

            status == 404 -> SyncFailure(
                SyncFailureKind.PermissionDenied,
                "The Folio Library folder is not accessible with this account. Reconnect it and try again.",
                retryable = false,
            )

            status == 429 -> rateLimited()

            status in 500..599 -> SyncFailure(
                SyncFailureKind.Server,
                "Google Drive is having trouble right now. Try again in a few minutes.",
                retryable = true,
            )

            else -> SyncFailure(
                SyncFailureKind.Unknown,
                "Google Drive couldn't complete sync. Try again.",
                retryable = status >= 500,
            )
        }
    }

    private fun offline() = SyncFailure(
        SyncFailureKind.Offline,
        "You're offline. Connect to the internet and try again.",
        retryable = true,
    )

    private fun rateLimited() = SyncFailure(
        SyncFailureKind.RateLimited,
        "Google Drive is busy. Try again in a few minutes.",
        retryable = true,
    )
}
