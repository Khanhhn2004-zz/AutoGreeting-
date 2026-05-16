package com.example.carchatbot.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object SoundPlayerState {
    data class PlaybackSnapshot(
        val isPlaying: Boolean = false,
        val currentSoundIndex: Int? = null,
        val activeRequest: PlaybackRequest? = null,
        val playingRequest: PlaybackRequest? = null,
        val startupPlaybackSoundIndex: Int? = null
    )

    private val stateLock = Any()
    private val _snapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = _snapshot.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentSoundIndex = MutableStateFlow<Int?>(null)
    val currentSoundIndex: StateFlow<Int?> = _currentSoundIndex.asStateFlow()

    private val _activeRequest = MutableStateFlow<PlaybackRequest?>(null)
    val activeRequest: StateFlow<PlaybackRequest?> = _activeRequest.asStateFlow()

    private val _playingRequest = MutableStateFlow<PlaybackRequest?>(null)
    val playingRequest: StateFlow<PlaybackRequest?> = _playingRequest.asStateFlow()

    private val _startupPlaybackSoundIndex = MutableStateFlow<Int?>(null)
    val startupPlaybackSoundIndex: StateFlow<Int?> = _startupPlaybackSoundIndex.asStateFlow()

    data class RequestState(
        val activeRequest: PlaybackRequest? = null,
        val isStartPending: Boolean = false
    ) {
        fun decisionFor(
            incoming: PlaybackRequest,
            cleanupInProgress: Boolean,
            autoplaySuppressed: Boolean
        ): PlaybackRequestDecision {
            if (incoming.isStartRequest && cleanupInProgress) {
                return PlaybackRequestDecision.REJECTED_DURING_CLEANUP
            }

            if (incoming.intent == PlaybackRequestIntent.AUTOPLAY_START && autoplaySuppressed) {
                return PlaybackRequestDecision.IGNORE_SUPPRESSED_AUTOPLAY
            }

            if (
                incoming.intent == PlaybackRequestIntent.USER_STOP &&
                isStartPending &&
                activeRequest?.intent == PlaybackRequestIntent.AUTOPLAY_START &&
                (incoming.soundIndex == null || incoming.soundIndex == activeRequest.soundIndex)
            ) {
                return PlaybackRequestDecision.CANCEL_PENDING_AUTOPLAY
            }

            if (
                incoming.intent == PlaybackRequestIntent.AUTOPLAY_START &&
                activeRequest?.intent == PlaybackRequestIntent.AUTOPLAY_START &&
                activeRequest.soundIndex == incoming.soundIndex
            ) {
                return PlaybackRequestDecision.IGNORE_DUPLICATE_AUTOPLAY
            }

            return PlaybackRequestDecision.ACCEPT
        }
    }

    fun snapshotRequestState(isStartPending: Boolean): RequestState {
        val currentSnapshot = snapshot.value
        return RequestState(
            activeRequest = currentSnapshot.activeRequest,
            isStartPending = isStartPending
        )
    }

    fun onPlaybackRequestQueued(request: PlaybackRequest) {
        updateSnapshot { current ->
            val samePlayingSlot = current.playingRequest?.soundIndex == request.soundIndex
            current.copy(
                activeRequest = request,
                currentSoundIndex = request.soundIndex,
                playingRequest = if (samePlayingSlot) current.playingRequest else null,
                isPlaying = if (samePlayingSlot) current.isPlaying else false
            )
        }
    }

    fun onPlaybackStarted(request: PlaybackRequest) {
        updateSnapshot { current ->
            current.copy(
                activeRequest = request,
                playingRequest = request,
                currentSoundIndex = request.soundIndex,
                isPlaying = true
            )
        }
    }

    fun onPlaybackPaused() {
        updateSnapshot { current ->
            current.copy(isPlaying = false)
        }
    }

    fun onPlaybackStopped() {
        updateSnapshot { current ->
            current.copy(
                activeRequest = null,
                playingRequest = null,
                currentSoundIndex = null,
                isPlaying = false
            )
        }
    }

    fun isManualPlaybackIdle(): Boolean {
        val currentSnapshot = snapshot.value
        return !currentSnapshot.isPlaying &&
            currentSnapshot.activeRequest == null &&
            currentSnapshot.playingRequest == null
    }

    fun clearManualPlaybackForTeardown() {
        onPlaybackStopped()
    }

    fun clearManualPlaybackForStartupTakeover() {
        onPlaybackStopped()
    }

    fun onStartupPlaybackStarted(soundIndex: Int) {
        updateSnapshot { current ->
            current.copy(startupPlaybackSoundIndex = soundIndex)
        }
    }

    fun onStartupPlaybackStopped() {
        updateSnapshot { current ->
            current.copy(startupPlaybackSoundIndex = null)
        }
    }

    fun resetForTest() {
        updateSnapshot { PlaybackSnapshot() }
    }

    private fun updateSnapshot(transform: (PlaybackSnapshot) -> PlaybackSnapshot) {
        synchronized(stateLock) {
            publishSnapshot(transform(_snapshot.value))
        }
    }

    private fun publishSnapshot(next: PlaybackSnapshot) {
        _snapshot.value = next
        _isPlaying.value = next.isPlaying
        _currentSoundIndex.value = next.currentSoundIndex
        _activeRequest.value = next.activeRequest
        _playingRequest.value = next.playingRequest
        _startupPlaybackSoundIndex.value = next.startupPlaybackSoundIndex
    }
}
