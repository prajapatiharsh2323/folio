package dev.folio.mobile.core.sync

import java.io.FileNotFoundException
import java.io.IOException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncFailureMapperTest {

    @Test
    fun `network failures are retryable and say offline`() {
        val failure = SyncFailureMapper.from(UnknownHostException("no dns"))
        assertEquals(SyncFailureKind.Offline, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.message.contains("no dns"))
    }

    @Test
    fun `generic io failures stay recoverable`() {
        val failure = SyncFailureMapper.from(IOException("socket closed"))
        assertEquals(SyncFailureKind.Offline, failure.kind)
        assertTrue(failure.retryable)
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
    fun `unknown failures never leak exception text`() {
        val failure = SyncFailureMapper.from(IllegalArgumentException("secret stack detail"))
        assertEquals(SyncFailureKind.Unknown, failure.kind)
        assertTrue(failure.retryable)
        assertFalse(failure.message.contains("secret"))
    }
}
