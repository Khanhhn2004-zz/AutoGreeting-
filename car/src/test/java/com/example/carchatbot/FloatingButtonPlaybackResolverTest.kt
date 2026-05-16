package com.example.carchatbot

import com.example.carchatbot.boot.BootPlaybackPhase
import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootSignalOrigin
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.runtime.FloatingButtonPlaybackAction
import com.example.carchatbot.service.FloatingButtonPlaybackResolver
import com.example.carchatbot.service.FloatingButtonSoundPlayerSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingButtonPlaybackResolverTest {

    @Test
    fun `startup slot stop plan stops both boot and sound player when both are active`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            bootState = activeBootState(),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                playingSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                currentSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                isPlaying = true
            ),
            startupPlaybackServiceRunning = true
        )

        assertEquals(FloatingButtonPlaybackAction.STOP, plan.action)
        assertTrue(plan.stopBootPlayback)
        assertTrue(plan.stopSoundPlayer)
    }

    @Test
    fun `startup slot stop plan stops only boot playback when sound player is idle`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            bootState = activeBootState(),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = null,
                playingSoundIndex = null,
                currentSoundIndex = null,
                isPlaying = false
            ),
            startupPlaybackServiceRunning = true
        )

        assertEquals(FloatingButtonPlaybackAction.STOP, plan.action)
        assertTrue(plan.stopBootPlayback)
        assertFalse(plan.stopSoundPlayer)
    }

    @Test
    fun `startup slot stop plan stops only sound player when boot playback is inactive`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            bootState = BootPlaybackState(
                startupWindowId = 11L,
                sessionId = "session-11",
                ownerOrigin = BootSignalOrigin.APP_AUTO_START,
                phase = BootPlaybackPhase.COMPLETED,
                lastProgressAt = 1_000L
            ),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                playingSoundIndex = null,
                currentSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                isPlaying = true
            ),
            startupPlaybackServiceRunning = false
        )

        assertEquals(FloatingButtonPlaybackAction.STOP, plan.action)
        assertFalse(plan.stopBootPlayback)
        assertTrue(plan.stopSoundPlayer)
    }

    @Test
    fun `startup slot ignores stale in-memory startup playback when boot service is not running`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            bootState = BootPlaybackState(
                startupWindowId = 12L,
                sessionId = "session-12",
                ownerOrigin = BootSignalOrigin.RECEIVER,
                phase = BootPlaybackPhase.FAILED,
                lastProgressAt = 6_000L,
                lastFailureReason = "stale_startup_ownership"
            ),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = null,
                playingSoundIndex = null,
                currentSoundIndex = null,
                startupPlaybackSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
                isPlaying = false
            ),
            startupPlaybackServiceRunning = false
        )

        assertEquals(FloatingButtonPlaybackAction.START, plan.action)
        assertFalse(plan.stopBootPlayback)
        assertFalse(plan.stopSoundPlayer)
    }

    @Test
    fun `startup slot tap ignores stale persisted boot playback when service is not running`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = BootSoundCacheRepository.STARTUP_SOUND_INDEX,
            bootState = activeBootState(),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = null,
                playingSoundIndex = null,
                currentSoundIndex = null,
                startupPlaybackSoundIndex = null,
                isPlaying = false
            ),
            startupPlaybackServiceRunning = false
        )

        assertEquals(FloatingButtonPlaybackAction.START, plan.action)
        assertFalse(plan.stopBootPlayback)
        assertFalse(plan.stopSoundPlayer)
    }

    @Test
    fun `non startup slot never stops boot playback`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = 2,
            bootState = activeBootState(),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = 2,
                playingSoundIndex = 2,
                currentSoundIndex = 2,
                isPlaying = true
            ),
            startupPlaybackServiceRunning = true
        )

        assertEquals(FloatingButtonPlaybackAction.STOP, plan.action)
        assertFalse(plan.stopBootPlayback)
        assertTrue(plan.stopSoundPlayer)
    }

    @Test
    fun `idle slot stays in start action`() {
        val plan = FloatingButtonPlaybackResolver.resolvePlan(
            tappedSoundIndex = 2,
            bootState = BootPlaybackState(),
            soundPlayerSnapshot = FloatingButtonSoundPlayerSnapshot(
                activeSoundIndex = null,
                playingSoundIndex = null,
                currentSoundIndex = null,
                isPlaying = false
            ),
            startupPlaybackServiceRunning = false
        )

        assertEquals(FloatingButtonPlaybackAction.START, plan.action)
        assertFalse(plan.stopBootPlayback)
        assertFalse(plan.stopSoundPlayer)
    }

    private fun activeBootState(): BootPlaybackState {
        return BootPlaybackState(
            startupWindowId = 10L,
            sessionId = "session-10",
            ownerOrigin = BootSignalOrigin.APP_AUTO_START,
            phase = BootPlaybackPhase.PLAYING,
            lastProgressAt = 1_000L
        )
    }
}
