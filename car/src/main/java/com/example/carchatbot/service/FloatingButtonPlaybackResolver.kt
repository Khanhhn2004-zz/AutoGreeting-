package com.example.carchatbot.service

import com.example.carchatbot.boot.BootPlaybackState
import com.example.carchatbot.boot.BootSoundCacheRepository
import com.example.carchatbot.runtime.FloatingButtonPlaybackAction

internal data class FloatingButtonSoundPlayerSnapshot(
    val activeSoundIndex: Int?,
    val playingSoundIndex: Int?,
    val currentSoundIndex: Int?,
    val startupPlaybackSoundIndex: Int? = null,
    val isPlaying: Boolean
) {
    fun hasPlaybackFor(soundIndex: Int): Boolean {
        return activeSoundIndex == soundIndex ||
            playingSoundIndex == soundIndex ||
            (isPlaying && currentSoundIndex == soundIndex)
    }

    fun hasStartupPlaybackFor(soundIndex: Int): Boolean {
        return startupPlaybackSoundIndex == soundIndex
    }
}

internal data class FloatingButtonPlaybackPlan(
    val action: FloatingButtonPlaybackAction,
    val stopBootPlayback: Boolean,
    val stopSoundPlayer: Boolean
)

internal object FloatingButtonPlaybackResolver {

    fun resolvePlan(
        tappedSoundIndex: Int,
        bootState: BootPlaybackState,
        soundPlayerSnapshot: FloatingButtonSoundPlayerSnapshot,
        startupPlaybackServiceRunning: Boolean
    ): FloatingButtonPlaybackPlan {
        val hasLiveBootPlaybackForStartupSlot =
            startupPlaybackServiceRunning && bootState.isActive()
        val shouldStopBootPlayback =
            tappedSoundIndex == BootSoundCacheRepository.STARTUP_SOUND_INDEX &&
                hasLiveBootPlaybackForStartupSlot
        val shouldStopSoundPlayer = soundPlayerSnapshot.hasPlaybackFor(tappedSoundIndex)

        return if (shouldStopBootPlayback || shouldStopSoundPlayer) {
            FloatingButtonPlaybackPlan(
                action = FloatingButtonPlaybackAction.STOP,
                stopBootPlayback = shouldStopBootPlayback,
                stopSoundPlayer = shouldStopSoundPlayer
            )
        } else {
            FloatingButtonPlaybackPlan(
                action = FloatingButtonPlaybackAction.START,
                stopBootPlayback = false,
                stopSoundPlayer = false
            )
        }
    }
}
