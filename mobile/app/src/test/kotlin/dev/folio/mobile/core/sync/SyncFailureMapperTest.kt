package dev.folio.mobile.core.sync

import com.google.api.client.googleapis.json.GoogleJsonError
import com.google.api.client.googleapis.json.GoogleJsonResponseException
import com.google.api.client.http.HttpHeaders
import com.google.api.client.http.HttpResponseException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncFailureMapperTest {

    private fun googleHttp(
        status: Int,
        reason: String? = null,
        message: String = "drive said no",
    ): GoogleJsonResponseException {
        val details = GoogleJsonError().apply {
            code = status
            setMessage(message)
            if (reason != null) {
                errors = listOf(GoogleJsonError.ErrorInfo().apply { setReason(reason) })
            }
        }
        return GoogleJsonResponseException(
            HttpResponseException.Builder(status, message, HttpHeaders()),
            details,
        )
    }

    @Test
    fun `network failures are retryable and say offline`() {
        val failure = SyncFailureMapper.from(UnknownHostException("no dns"))
        assertEquals(SyncFailureKind.Offline, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.message.contains("no dns"))
    }

    @Test
    fun `connect timeout and socket failures are offline`() {
        listOf(ConnectException("refused"), SocketTimeoutException("timed out"), SSLException("tls"))
            .forEach { error ->
                val failure = SyncFailureMapper.from(error)
                assertEquals(SyncFailureKind.Offline, failure.kind)
                assertTrue(failure.retryable)
            }
    }

    @Test
    fun `io exception with a dns or socket cause is a connection problem`() {
        val dns = IOException("request failed").also { it.initCause(UnknownHostException("drive")) }
        val timeout = IOException("request failed").also {
            it.initCause(SocketTimeoutException("read timed out"))
        }
        listOf(dns, timeout).forEach { error ->
            assertEquals(SyncFailureKind.Offline, SyncFailureMapper.from(error).kind)
        }
    }

    @Test
    fun `generic io failures stay recoverable and never claim the phone is offline`() {
        val failure = SyncFailureMapper.from(IOException("socket closed"))
        assertEquals(SyncFailureKind.Unknown, failure.kind)
        assertTrue(failure.retryable)
        assertFalse("must not diagnose offline on unknown I/O", failure.message.contains("offline", true))
    }

    @Test
    fun `security failure means the saved account is gone`() {
        val failure = SyncFailureMapper.from(SecurityException("denied"))
        assertEquals(SyncFailureKind.AccountMissing, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `missing local file is a storage problem, not offline`() {
        val failure = SyncFailureMapper.from(FileNotFoundException("sync/state.json"))
        assertEquals(SyncFailureKind.Storage, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `http 401 needs drive consent again`() {
        val failure = SyncFailureMapper.from(googleHttp(401))
        assertEquals(SyncFailureKind.AuthRequired, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `http 403 permission denied is not retryable`() {
        val failure = SyncFailureMapper.from(googleHttp(403, reason = "insufficientPermissions"))
        assertEquals(SyncFailureKind.PermissionDenied, failure.kind)
        assertFalse(failure.retryable)
    }

    @Test
    fun `http 403 rate limit reasons are retry-later, not permission loss`() {
        listOf("rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded").forEach { reason ->
            val failure = SyncFailureMapper.from(googleHttp(403, reason = reason))
            assertEquals(reason, SyncFailureKind.RateLimited, failure.kind)
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun `http 403 without a reason body is permission denied`() {
        val failure = SyncFailureMapper.from(googleHttp(403))
        assertEquals(SyncFailureKind.PermissionDenied, failure.kind)
    }

    @Test
    fun `http 404 means the drive mirror is not accessible`() {
        val failure = SyncFailureMapper.from(googleHttp(404, message = "File not found: folio-library"))
        assertEquals(SyncFailureKind.PermissionDenied, failure.kind)
        assertEquals(
            "The Folio Library folder is not accessible with this account. Reconnect it and try again.",
            failure.message,
        )
    }

    @Test
    fun `http 429 is rate limiting and retryable`() {
        val failure = SyncFailureMapper.from(googleHttp(429, reason = "rateLimitExceeded"))
        assertEquals(SyncFailureKind.RateLimited, failure.kind)
        assertTrue(failure.retryable)
    }

    @Test
    fun `http 5xx is a temporary server failure and retryable`() {
        for (status in intArrayOf(500, 502, 503)) {
            val failure = SyncFailureMapper.from(googleHttp(status))
            assertEquals(SyncFailureKind.Server, failure.kind)
            assertTrue(failure.retryable)
        }
    }

    @Test
    fun `http statuses wrapped inside an io exception are still honoured`() {
        val wrapped = IOException("request failed")
            .also { it.initCause(googleHttp(429, reason = "rateLimitExceeded")) }
        assertEquals(SyncFailureKind.RateLimited, SyncFailureMapper.from(wrapped).kind)
    }

    @Test
    fun `cancellation is rethrown, never mapped to a failure state`() {
        try {
            SyncFailureMapper.from(CancellationException("worker stopped"))
            fail("cancellation must propagate")
        } catch (expected: CancellationException) {
            // Rethrown as required.
        }
    }

    @Test
    fun `unknown failures never leak exception text`() {
        val failure = SyncFailureMapper.from(IllegalArgumentException("secret stack detail"))
        assertEquals(SyncFailureKind.Unknown, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.message.contains("secret"))
    }
}
