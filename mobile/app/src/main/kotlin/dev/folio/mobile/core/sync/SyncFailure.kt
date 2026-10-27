package dev.folio.mobile.core.sync

import com.google.android.gms.auth.GoogleAuthException
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Sync stopped because local or mirrored user data exists but cannot be
 * decoded. This is deliberately distinct from a transport failure: retrying
 * cannot fix it, and the UI must tell the user which data to repair.
 */
class SyncDataUnreadableException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

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
    /** Saved notes/progress/collections exist but cannot be read. */
    DataUnreadable,
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

    /**
     * Classifies a sync failure by exception semantics, not by class name alone.
     *
     * The installed Google HTTP stack reports most failures as an
     * [java.io.IOException] subclass, so the first edition of this mapper chose
     * "offline" for every IOException — which told people their phone was
     * offline when the real problem was an expired token, a permission loss or
     * local storage trouble. Classification therefore walks the whole cause
     * chain for the most specific signal first:
     *
     *  1. recoverable Drive consent (`UserRecoverableAuthIOException`)
     *  2. a Google HTTP response status (401/403/404/429/5xx)
     *  3. Google auth / account problems
     *  4. local file problems (`FileNotFoundException`)
     *  5. specific network-transport types (DNS/connect/timeout/socket/SSL)
     *  6. any remaining I/O — reported as a neutral retryable sync error, never
     *     as a confidently-diagnosed "you are offline"
     */
    fun from(error: Throwable): SyncFailure {
        // A cancelled sync is not a failure and must never surface as one.
        if (error is CancellationException) throw error

        val chain = chainOf(error)

        chain.firstOrNull { it is SyncDataUnreadableException }?.let {
            return SyncFailure(
                SyncFailureKind.DataUnreadable,
                it.message ?: "Saved library data can't be read. Sync stopped without changing it.",
                retryable = false,
            )
        }
        chain.firstOrNull { it is UserRecoverableAuthIOException }?.let { return authRequired() }
        chain.firstOrNull { it is GoogleJsonResponseException }?.let {
            return fromHttp(it as GoogleJsonResponseException)
        }
        chain.firstOrNull { it is GoogleAuthException || it is SecurityException }?.let {
            return accountMissing()
        }
        // Local files the app cannot read are a storage problem even when they
        // surface through the transport layer.
        chain.firstOrNull { it is FileNotFoundException }?.let { return storage() }

        if (chain.any { isNetworkTransport(it) }) return offline()

        if (chain.firstOrNull() is IllegalStateException &&
            chain.first().message?.contains("account", true) == true
        ) {
            return accountMissing()
        }

        return neutralRetryable()
    }

    private fun fromHttp(error: GoogleJsonResponseException): SyncFailure {
        val status = error.statusCode
        val reasons = error.details?.errors
            ?.joinToString(" ") { it.reason.orEmpty() }
            .orEmpty()
        val message = (error.details?.message ?: error.message).orEmpty()
        return fromHttpStatus(status, "$reasons $message")
    }

    private fun fromHttpStatus(status: Int, detail: String): SyncFailure = when {
        status == 401 -> authRequired()

        status == 403 && isRateLimitDetail(detail) -> rateLimited()

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

    /** 403 reasons Drive uses for throttling rather than for permission loss. */
    private val RATE_LIMIT_REASONS = listOf(
        "rateLimitExceeded",
        "userRateLimitExceeded",
        "sharingRateLimitExceeded",
        "dailyLimitExceeded",
    )

    private fun isRateLimitDetail(detail: String): Boolean {
        val lowered = detail.lowercase()
        return RATE_LIMIT_REASONS.any { lowered.contains(it.lowercase()) }
    }

    private fun chainOf(error: Throwable): List<Throwable> {
        val seen = HashSet<Throwable>()
        val list = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && seen.add(current)) {
            list.add(current)
            current = current.cause
        }
        return list
    }

    /**
     * Transport-level signals that genuinely indicate the device could not
     * reach the network or the server. Plain IOExceptions are deliberately NOT
     * here: the Google stack also throws IOException for local reads and
     * transport setup, which is not evidence the phone is offline.
     */
    private fun isNetworkTransport(error: Throwable): Boolean = when (error) {
        is UnknownHostException, // DNS
        is ConnectException, // TCP connect refused / unreachable
        is SocketTimeoutException, // connect or read timed out
        is SocketException, // connection reset, network unreachable, abort
        is SSLException, // TLS handshake/handoff failure
        -> true
        else -> false
    }

    internal fun offline() = SyncFailure(
        SyncFailureKind.Offline,
        "You're offline. Connect to the internet and try again.",
        retryable = true,
    )

    private fun authRequired() = SyncFailure(
        SyncFailureKind.AuthRequired,
        "Folio needs permission to use Google Drive. Grant access and try again.",
        retryable = false,
    )

    private fun accountMissing() = SyncFailure(
        SyncFailureKind.AccountMissing,
        "That Google account is no longer available. Reconnect it and try again.",
        retryable = false,
    )

    private fun storage() = SyncFailure(
        SyncFailureKind.Storage,
        "A file needed for sync could not be read. Check the library and try again.",
        retryable = false,
    )

    private fun rateLimited() = SyncFailure(
        SyncFailureKind.RateLimited,
        "Google Drive is busy. Try again in a few minutes.",
        retryable = true,
    )

    private fun neutralRetryable() = SyncFailure(
        SyncFailureKind.Unknown,
        "Sync didn't finish. Check your connection and try again.",
        retryable = true,
    )
}
