package com.example.carchatbot.service

import android.content.Intent

enum class PlaybackRequestIntent {
    AUTOPLAY_START,
    USER_START,
    USER_STOP,
    USER_TOGGLE
}

enum class PlaybackRequestDecision {
    ACCEPT,
    IGNORE_DUPLICATE_AUTOPLAY,
    IGNORE_SUPPRESSED_AUTOPLAY,
    REJECTED_DURING_CLEANUP,
    CANCEL_PENDING_AUTOPLAY
}

data class PlaybackRequest(
    val soundIndex: Int?,
    val intent: PlaybackRequestIntent,
    val finishActivityOnCompletion: Boolean = false,
    val forceReplay: Boolean = false,
    val requestId: Long = 0L
) {
    val isStartRequest: Boolean
        get() = intent != PlaybackRequestIntent.USER_STOP

    val shouldSuppressAutoplay: Boolean
        get() = intent == PlaybackRequestIntent.USER_STOP || intent == PlaybackRequestIntent.USER_TOGGLE

    val clearsAutoplaySuppression: Boolean
        get() = intent == PlaybackRequestIntent.USER_START || intent == PlaybackRequestIntent.USER_TOGGLE

    val prefersImmediateSourceRefresh: Boolean
        get() = intent == PlaybackRequestIntent.USER_START || intent == PlaybackRequestIntent.USER_TOGGLE

    val isUserInitiated: Boolean
        get() = intent == PlaybackRequestIntent.USER_START || intent == PlaybackRequestIntent.USER_TOGGLE || intent == PlaybackRequestIntent.USER_STOP

    fun withRequestId(nextRequestId: Long): PlaybackRequest = copy(requestId = nextRequestId)

    companion object {
        fun autoplayStart(soundIndex: Int): PlaybackRequest =
            PlaybackRequest(soundIndex = soundIndex, intent = PlaybackRequestIntent.AUTOPLAY_START)

        fun userStart(soundIndex: Int): PlaybackRequest =
            PlaybackRequest(soundIndex = soundIndex, intent = PlaybackRequestIntent.USER_START)

        fun userStop(soundIndex: Int? = null): PlaybackRequest =
            PlaybackRequest(soundIndex = soundIndex, intent = PlaybackRequestIntent.USER_STOP)

        fun userToggle(soundIndex: Int): PlaybackRequest =
            PlaybackRequest(
                soundIndex = soundIndex,
                intent = PlaybackRequestIntent.USER_TOGGLE,
                forceReplay = true
            )

        fun fromServiceIntent(intent: Intent?, defaultSoundIndex: Int): PlaybackRequest {
            val resolvedSoundIndex = intent?.getIntExtra(
                SoundPlayerService.EXTRA_SOUND_URI_INDEX,
                defaultSoundIndex
            )?.takeIf { it != 0 } ?: defaultSoundIndex
            val finishActivityOnCompletion = intent?.getBooleanExtra(
                SoundPlayerService.EXTRA_FINISH_ACTIVITY_ON_COMPLETION,
                false
            ) ?: false
            val forceReplay = intent?.getBooleanExtra(
                SoundPlayerService.EXTRA_FORCE_REPLAY,
                false
            ) ?: false
            val explicitIntent = intent?.getStringExtra(SoundPlayerService.EXTRA_PLAYBACK_REQUEST_INTENT)
                ?.let { requestIntentName ->
                    runCatching { PlaybackRequestIntent.valueOf(requestIntentName) }.getOrNull()
                }

            val requestIntent = explicitIntent ?: when {
                intent?.action == SoundPlayerService.ACTION_STOP_SOUND ->
                    PlaybackRequestIntent.USER_STOP

                finishActivityOnCompletion ->
                    PlaybackRequestIntent.AUTOPLAY_START

                forceReplay ->
                    PlaybackRequestIntent.USER_TOGGLE

                else ->
                    PlaybackRequestIntent.USER_START
            }

            return PlaybackRequest(
                soundIndex = if (requestIntent == PlaybackRequestIntent.USER_STOP) {
                    intent?.getIntExtra(SoundPlayerService.EXTRA_SOUND_URI_INDEX, 0)?.takeIf { it != 0 }
                } else {
                    resolvedSoundIndex
                },
                intent = requestIntent,
                finishActivityOnCompletion = finishActivityOnCompletion,
                forceReplay = forceReplay
            )
        }
    }
}
