package com.example.carchatbot

import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.StartupExecutionStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BootPlaybackServiceTest {

    @Test
    fun `service accepts matching active session ownership`() {
        val accepted = verifySessionOwnership(
            state = BootPlaybackState(
                startupWindowId = 42L,
                sessionId = "session-42",
                ownerOrigin = BootSignalOrigin.RECEIVER,
                phase = BootPlaybackPhase.PLAYING,
                lastProgressAt = 1_000L
            ),
            requestedSessionId = "session-42",
            requestedWindowId = 42L
        )

        assertTrue(accepted)
    }

    @Test
    fun `service rejects inactive or mismatched ownership`() {
        val inactive = verifySessionOwnership(
            state = BootPlaybackState(
                startupWindowId = 42L,
                sessionId = "session-42",
                ownerOrigin = BootSignalOrigin.RECEIVER,
                phase = BootPlaybackPhase.COMPLETED,
                lastProgressAt = 1_000L
            ),
            requestedSessionId = "session-42",
            requestedWindowId = 42L
        )
        val wrongWindow = verifySessionOwnership(
            state = BootPlaybackState(
                startupWindowId = 42L,
                sessionId = "session-42",
                ownerOrigin = BootSignalOrigin.APP_AUTO_START,
                phase = BootPlaybackPhase.STARTING,
                lastProgressAt = 1_000L
            ),
            requestedSessionId = "session-42",
            requestedWindowId = 99L
        )

        assertFalse(inactive)
        assertFalse(wrongWindow)
    }

    @Test
    fun `foreground startup service only starts for direct execution strategies`() {
        assertTrue(
            BootPlaybackService.shouldStartForegroundService(
                StartupExecutionStrategy.START_FOREGROUND_SERVICE_NOW
            )
        )
        assertFalse(
            BootPlaybackService.shouldStartForegroundService(
                StartupExecutionStrategy.DEFER_UNTIL_PROCESS_VISIBLE
            )
        )
    }

    @Test
    fun `manual preemption helper records active startup playback as manually stopped`() {
        val storageDir = Files.createTempDirectory("boot-playback-manual-preemption").toFile()
        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 7L,
            sessionId = "session-7",
            ownerOrigin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )

        val updated = BootPlaybackService.applyManualPreemptionFailure(
            stateStore = store,
            reason = "floating_button_stop",
            nowMillis = 2_000L
        )

        assertEquals(BootPlaybackPhase.MANUALLY_STOPPED, updated.phase)
        assertEquals("floating_button_stop", updated.lastFailureReason)
        assertEquals(2_000L, updated.lastProgressAt)
    }

    @Test
    fun `manual preemption helper leaves terminal startup playback state untouched`() {
        val storageDir = Files.createTempDirectory("boot-playback-manual-preemption-terminal").toFile()
        val store = BootPlaybackStateStore(storageDir)
        store.claimSession(
            startupWindowId = 8L,
            sessionId = "session-8",
            ownerOrigin = BootSignalOrigin.RECEIVER,
            nowMillis = 1_000L
        )
        store.markCompleted(
            sessionId = "session-8",
            nowMillis = 1_500L
        )

        val unchanged = BootPlaybackService.applyManualPreemptionFailure(
            stateStore = store,
            reason = "floating_button_stop",
            nowMillis = 2_000L
        )

        assertEquals(BootPlaybackPhase.COMPLETED, unchanged.phase)
        assertEquals(null, unchanged.lastFailureReason)
    }

    private fun verifySessionOwnership(
        state: BootPlaybackState,
        requestedSessionId: String?,
        requestedWindowId: Long?
    ): Boolean {
        val method = BootPlaybackService::class.java.getDeclaredMethod(
            "verifySessionOwnership",
            BootPlaybackState::class.java,
            String::class.java,
            java.lang.Long::class.java
        )
        method.isAccessible = true
        return method.invoke(BootPlaybackService(), state, requestedSessionId, requestedWindowId) as Boolean
    }
}
