package com.example.carchatbot

import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.text.Charsets.UTF_8

class BootPlaybackStateStoreSourceTest {

    private fun readSource(relativePath: String): String =
        String(Files.readAllBytes(Paths.get(relativePath)), UTF_8)

    @Test
    fun `empty store starts idle with no persisted ownership`() {
        val storageDir = Files.createTempDirectory("boot-playback-state").toFile()

        val store = BootPlaybackStateStore(storageDir)

        val state = store.readState()

        assertEquals(BootPlaybackPhase.IDLE, state.phase)
        assertNull(state.startupWindowId)
        assertNull(state.sessionId)
        assertNull(state.ownerOrigin)
        assertEquals(0, state.takeoverCount)
        assertNull(state.lastFailureReason)
        assertNull(state.lastNotificationStartupWindowId)
    }

    @Test
    fun `claiming a session persists the boot window and ownership fields`() {
        val storageDir = Files.createTempDirectory("boot-playback-state").toFile()

        val store = BootPlaybackStateStore(storageDir)

        val claimed = store.claimSession(
            startupWindowId = 42L,
            sessionId = "session-42",
            ownerOrigin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        val reloaded = BootPlaybackStateStore(storageDir).readState()

        assertEquals(BootPlaybackPhase.CLAIMED, claimed.phase)
        assertEquals(42L, claimed.startupWindowId)
        assertEquals("session-42", claimed.sessionId)
        assertEquals(BootSignalOrigin.RECEIVER, claimed.ownerOrigin)
        assertEquals(1_000L, claimed.lastProgressAt)
        assertEquals(0, claimed.takeoverCount)
        assertEquals(claimed, reloaded)
    }

    @Test
    fun `marking a failed session stores the failure reason without losing ownership`() {
        val storageDir = Files.createTempDirectory("boot-playback-state").toFile()

        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 42L,
            sessionId = "session-42",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_000L
        )

        val failed = store.markFailed(
            sessionId = "session-42",
            failureReason = "cache-missing",
            nowMillis = 2_000L
        )

        assertEquals(BootPlaybackPhase.FAILED, failed.phase)
        assertEquals("session-42", failed.sessionId)
        assertEquals(BootSignalOrigin.APP_AUTO_START, failed.ownerOrigin)
        assertEquals("cache-missing", failed.lastFailureReason)
        assertEquals(2_000L, failed.lastProgressAt)
    }

    @Test
    fun `stale active session expires into failed state but fresh active session stays active`() {
        val storageDir = Files.createTempDirectory("boot-playback-state").toFile()
        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 42L,
            sessionId = "session-42",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_000L
        )

        val stillActive = store.expireStaleActiveSession(
            nowMillis = 1_001L,
            staleTimeoutMillis = 5_000L,
            failureReason = "stale"
        )
        val expired = store.expireStaleActiveSession(
            nowMillis = 7_000L,
            staleTimeoutMillis = 5_000L,
            failureReason = "stale"
        )

        assertEquals(BootPlaybackPhase.CLAIMED, stillActive.phase)
        assertEquals(BootPlaybackPhase.FAILED, expired.phase)
        assertEquals("stale", expired.lastFailureReason)
    }

    @Test
    fun `terminal playback session is ignored by stale expiration helper`() {
        val storageDir = Files.createTempDirectory("boot-playback-state").toFile()
        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 99L,
            sessionId = "session-99",
            ownerOrigin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        store.markCompleted(
            sessionId = "session-99",
            nowMillis = 2_000L
        )

        val unchanged = store.expireStaleActiveSession(
            nowMillis = 20_000L,
            staleTimeoutMillis = 5_000L,
            failureReason = "should_not_overwrite_terminal_state"
        )

        assertEquals(BootPlaybackPhase.COMPLETED, unchanged.phase)
        assertEquals("session-99", unchanged.sessionId)
    }

    @Test
    fun `playing session is preserved by stale expiration helper while playback is already underway`() {
        val storageDir = Files.createTempDirectory("boot-playback-state").toFile()
        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 100L,
            sessionId = "session-100",
            ownerOrigin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        store.markPlaying(
            sessionId = "session-100",
            nowMillis = 1_500L
        )

        val unchanged = store.expireStaleActiveSession(
            nowMillis = 20_000L,
            staleTimeoutMillis = 5_000L,
            failureReason = "stale"
        )

        assertEquals(BootPlaybackPhase.PLAYING, unchanged.phase)
        assertEquals("session-100", unchanged.sessionId)
        assertNull(unchanged.lastFailureReason)
    }

    @Test
    fun `safe terminal callback update ignores stale session when state was already cleared`() {
        val storageDir = Files.createTempDirectory("boot-playback-state-stale-callback").toFile()
        val store = BootPlaybackStateStore(storageDir)

        val updated = store.markCompletedIfCurrent(
            sessionId = "stale-session",
            nowMillis = 2_000L
        )

        assertFalse(updated)
        assertEquals(BootPlaybackPhase.IDLE, store.readState().phase)
        assertNull(store.readState().sessionId)
    }

    @Test
    fun `safe terminal callback update writes only the current matching session`() {
        val storageDir = Files.createTempDirectory("boot-playback-state-current-callback").toFile()
        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 101L,
            sessionId = "session-101",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            nowMillis = 1_000L
        )

        val updated = store.markCompletedIfCurrent(
            sessionId = "session-101",
            nowMillis = 2_000L
        )

        assertTrue(updated)
        assertEquals(BootPlaybackPhase.COMPLETED, store.readState().phase)
        assertEquals("session-101", store.readState().sessionId)
    }

    @Test
    fun `state store uses boot storage context provider instead of credential protected files dir`() {
        val source = readSource("src/main/java/com/example/carchatbot/boot/BootPlaybackStateStore.kt")

        assertTrue(source.contains("BootStorageContextProvider"))
        assertTrue(source.contains("startupStorageDir(STORAGE_DIRECTORY_NAME)"))
        assertTrue(source.contains("STATE_FILE_NAME"))
        assertTrue(!source.contains("context.filesDir"))
    }
}
