package com.example.carchatbot.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.carchatbot.R
import com.example.carchatbot.audio.PlaybackAudioProfile
import com.example.carchatbot.boot.BootPlaybackService
import com.example.carchatbot.boot.BootPlaybackStateStore
import com.example.carchatbot.data.local.UserPreferencesRepository
import com.example.carchatbot.data.repository.PlaybackSoundResolution
import com.example.carchatbot.data.repository.PlaybackSoundResolver
import com.example.carchatbot.runtime.AppRuntimePolicies
import com.example.carchatbot.runtime.PlaybackTerminationReason
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SoundPlayerService : Service() {

    @Inject
    lateinit var playbackSoundResolver: PlaybackSoundResolver

    @Inject
    lateinit var userPreferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var appLogger: com.example.carchatbot.utils.AppLogger

    @Inject
    lateinit var bootPlaybackStateStore: BootPlaybackStateStore

    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingUri: Uri? = null
    private var pendingPlaybackUri: String? = null
    private var pendingSoundIndex: Int? = null
    private var isPlaybackStartPending = false
    private var playbackToken = 0
    private var playbackRequestCounter = 0L
    private var finishActivityOnCompletion = false
    private var startTimeoutJob: Job? = null
    private val playerLock = Any()
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.d("SoundPlayerService", "Audio focus gained. Attempting guarded resume.")
                safeStartPlaybackFromFocusGain("audio_focus_gain")
            }

            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.d("SoundPlayerService", "Audio focus lost. Attempting guarded pause.")
                safePausePlaybackFromFocusLoss()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        appLogger.logService("SoundPlayerService", "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val playbackRequest = PlaybackRequest.fromServiceIntent(
            intent = intent,
            defaultSoundIndex = DEFAULT_SOUND_INDEX
        )
        preemptStartupPlaybackIfNeeded(playbackRequest)

        if (playbackRequest.intent == PlaybackRequestIntent.USER_STOP) {
            appLogger.logAudio("SoundPlayerService", "Stop action received")
            val autoplaySoundIndexToSuppress = SoundPlayerState.snapshot.value.activeRequest
                ?.takeIf { it.intent == PlaybackRequestIntent.AUTOPLAY_START }
                ?.soundIndex
                ?: playbackRequest.soundIndex
            serviceScope.launch {
                latchAutoplaySuppression(autoplaySoundIndexToSuppress)
            }
            if (
                AppRuntimePolicies.shouldBroadcastPlaybackFinished(
                    finishActivityOnCompletion = finishActivityOnCompletion,
                    terminationReason = PlaybackTerminationReason.EXPLICIT_STOP
                )
            ) {
                sendPlaybackFinishedBroadcast()
            }
            sendCancelAutoReturnBroadcast()
            stopPlaybackSession("explicit_stop")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        finishActivityOnCompletion = playbackRequest.finishActivityOnCompletion

        serviceScope.launch {
            val resolvedSoundIndex = playbackRequest.soundIndex ?: DEFAULT_SOUND_INDEX
            val requestDecision = SoundPlayerState
                .snapshotRequestState(isStartPending = synchronized(playerLock) { isPlaybackStartPending })
                .decisionFor(
                    incoming = playbackRequest,
                    cleanupInProgress = userPreferencesRepository.cleanupInProgress.first(),
                    autoplaySuppressed = isAutoplaySuppressed(resolvedSoundIndex)
                )

            when (requestDecision) {
                PlaybackRequestDecision.REJECTED_DURING_CLEANUP -> {
                    appLogger.logAudio(
                        "SoundPlayerService",
                        "Rejecting playback request during cleanup for slot $resolvedSoundIndex"
                    )
                    stopSelf()
                    return@launch
                }

                PlaybackRequestDecision.IGNORE_SUPPRESSED_AUTOPLAY -> {
                    appLogger.logAudio(
                        "SoundPlayerService",
                        "Ignoring suppressed autoplay for slot $resolvedSoundIndex"
                    )
                    stopSelf()
                    return@launch
                }

                PlaybackRequestDecision.IGNORE_DUPLICATE_AUTOPLAY -> {
                    appLogger.logAudio(
                        "SoundPlayerService",
                        "Ignoring duplicate autoplay request for slot $resolvedSoundIndex"
                    )
                    stopSelf()
                    return@launch
                }

                PlaybackRequestDecision.CANCEL_PENDING_AUTOPLAY -> {
                    latchAutoplaySuppression(resolvedSoundIndex)
                    sendCancelAutoReturnBroadcast()
                    stopPlaybackSession("user_stop_cancelled_pending_autoplay")
                    stopSelf()
                    return@launch
                }

                PlaybackRequestDecision.ACCEPT -> Unit
            }

            if (playbackRequest.clearsAutoplaySuppression) {
                clearAutoplaySuppression(resolvedSoundIndex)
            }

            val acceptedRequest = enqueuePlaybackRequest(playbackRequest)
            val resolvedSound = resolvePlaybackSound(acceptedRequest)

            if (!isActiveRequest(acceptedRequest)) {
                return@launch
            }

            if (resolvedSound == null) {
                SoundPlayerState.onPlaybackStopped()
                notifyPlaybackUnavailable(
                    reason = "No playable sound was available for playback",
                    soundIndex = resolvedSoundIndex,
                    playbackRequest = acceptedRequest
                )
                return@launch
            }

            val activeUri = synchronized(playerLock) {
                currentPlayingUri?.toString() ?: pendingPlaybackUri
            }
            val startPending = synchronized(playerLock) { isPlaybackStartPending }

            if (
                AppRuntimePolicies.shouldIgnorePlaybackRequest(
                    isPlaying = SoundPlayerState.snapshot.value.isPlaying,
                    isStartPending = startPending,
                    currentUri = activeUri,
                    requestedUri = resolvedSound.uriString,
                    forceReplay = acceptedRequest.forceReplay
                )
            ) {
                appLogger.logAudio(
                    "SoundPlayerService",
                    "Ignoring duplicate playback request for ${resolvedSound.uriString}"
                )
                return@launch
            }

            playSound(
                playbackRequest = acceptedRequest,
                resolvedSound = resolvedSound
            )
        }

        return START_NOT_STICKY
    }

    private fun preemptStartupPlaybackIfNeeded(playbackRequest: PlaybackRequest) {
        if (!shouldPreemptStartupPlayback(playbackRequest, bootPlaybackStateStore.readState().isActive())) {
            return
        }

        BootPlaybackService.requestStopForManualPreemption(
            context = this,
            reason = "manual_${playbackRequest.intent.name.lowercase()}",
            stateStore = bootPlaybackStateStore
        )
    }

    private suspend fun resolvePlaybackSound(playbackRequest: PlaybackRequest): PlaybackSoundResolution? {
        val soundIndex = playbackRequest.soundIndex ?: DEFAULT_SOUND_INDEX
        return playbackSoundResolver.resolve(
            soundIndex = soundIndex,
            preferRefresh = playbackRequest.prefersImmediateSourceRefresh
        )
    }

    private fun playSound(
        playbackRequest: PlaybackRequest,
        resolvedSound: PlaybackSoundResolution
    ) {
        val soundUri = Uri.parse(resolvedSound.uriString)
        val soundIndex = resolvedSound.soundIndex
        stopPlaybackSession("starting_new_playback")
        markPlaybackRequest(soundUri, soundIndex, playbackRequest)

        val localFile = validateLocalFileUri(soundUri)
        if (soundUri.scheme == "file" && localFile == null) {
            handlePlaybackFailure(
                failedUri = soundUri,
                soundIndex = soundIndex,
                reason = "Stored file missing or invalid",
                playbackRequest = playbackRequest
            )
            return
        }

        Log.d("SoundPlayerService", "URI to play: $soundUri")
        appLogger.logAudio("SoundPlayerService", "Starting playback: $soundUri")

        try {
            val newPlayer = MediaPlayer()
            val playerToken = claimPendingPlayerOwnership(newPlayer, playbackRequest) ?: run {
                newPlayer.release()
                return
            }

            newPlayer.setAudioAttributes(PlaybackAudioProfile.audioAttributes)

            if (localFile != null) {
                newPlayer.setDataSource(localFile.absolutePath)
            } else {
                newPlayer.setDataSource(this, soundUri)
            }

            newPlayer.setOnPreparedListener { preparedPlayer ->
                if (!isCurrentPlayer(preparedPlayer, playerToken)) {
                    return@setOnPreparedListener
                }

                Log.d("SoundPlayerService", "MediaPlayer prepared. Requesting audio focus.")
                when (requestAudioFocus()) {
                    AudioFocusAcquireResult.GRANTED -> {
                        safeStartPreparedPlayer(
                            player = preparedPlayer,
                            playbackUri = soundUri,
                            playbackRequest = playbackRequest,
                            token = playerToken,
                            failureReason = "prepared_start_failed"
                        )
                    }

                    AudioFocusAcquireResult.DELAYED -> {
                        Log.d("SoundPlayerService", "Audio focus delayed. Waiting for callback.")
                    }

                    AudioFocusAcquireResult.FAILED -> {
                        terminatePlaybackUnavailable(
                            reason = "audio_focus_request_failed",
                            soundIndex = playbackRequest.soundIndex,
                            playbackRequest = playbackRequest
                        )
                    }
                }
            }

            newPlayer.setOnCompletionListener { completedPlayer ->
                if (!isCurrentPlayer(completedPlayer, playerToken)) {
                    return@setOnCompletionListener
                }

                Log.d("SoundPlayerService", "MediaPlayer playback completed")
                appLogger.logAudio("SoundPlayerService", "Playback completed successfully")
                if (
                    AppRuntimePolicies.shouldBroadcastPlaybackFinished(
                        finishActivityOnCompletion = finishActivityOnCompletion,
                        terminationReason = PlaybackTerminationReason.COMPLETED
                    )
                ) {
                    sendPlaybackFinishedBroadcast()
                }
                stopPlaybackSession("completed")
                stopSelf()
            }

            newPlayer.setOnErrorListener { errorPlayer, what, extra ->
                if (!isCurrentPlayer(errorPlayer, playerToken)) {
                    return@setOnErrorListener true
                }

                Log.e("SoundPlayerService", "MediaPlayer error: what=$what, extra=$extra")
                appLogger.logAudio(
                    "SoundPlayerService",
                    "Playback error: what=$what, extra=$extra"
                )
                handlePlaybackFailure(
                    failedUri = soundUri,
                    soundIndex = playbackRequest.soundIndex,
                    reason = "MediaPlayer error: what=$what, extra=$extra",
                    playbackRequest = playbackRequest
                )
                true
            }

            newPlayer.prepareAsync()
            Log.d("SoundPlayerService", "MediaPlayer preparing async")
        } catch (e: Exception) {
            Log.e("SoundPlayerService", "Error creating MediaPlayer", e)
            appLogger.logException("SoundPlayerService", e, "Failed to create MediaPlayer")
            handlePlaybackFailure(
                failedUri = soundUri,
                soundIndex = playbackRequest.soundIndex,
                reason = "Failed to create MediaPlayer: ${e.message}",
                playbackRequest = playbackRequest
            )
        }
    }

    private fun validateLocalFileUri(soundUri: Uri): File? {
        if (soundUri.scheme != "file") {
            return null
        }

        val path = soundUri.path ?: return null
        val file = File(path)
        return if (file.exists() && file.length() > 0L) file else null
    }

    private fun safeStartPreparedPlayer(
        player: MediaPlayer,
        playbackUri: Uri,
        playbackRequest: PlaybackRequest,
        token: Int,
        failureReason: String
    ) {
        if (!isCurrentPlayer(player, token)) {
            return
        }

        runCatching {
            if (!player.isPlaying) {
                player.start()
            }
            synchronized(playerLock) {
                if (!isCurrentPlayer(player, token)) {
                    return@runCatching
                }
                isPlaybackStartPending = false
                currentPlayingUri = playbackUri
            }
            cancelStartTimeout()
            SoundPlayerState.onPlaybackStarted(playbackRequest)
        }.onFailure { throwable ->
            appLogger.logException("SoundPlayerService", throwable, failureReason)
            terminatePlaybackUnavailable(
                reason = failureReason,
                soundIndex = playbackRequest.soundIndex,
                playbackRequest = playbackRequest
            )
        }
    }

    private fun safeStartPlaybackFromFocusGain(reason: String) {
        val snapshot = snapshotPlaybackState() ?: return
        val playbackUri = snapshot.uri ?: return

        runCatching {
            if (!snapshot.player.isPlaying) {
                snapshot.player.start()
            }
            synchronized(playerLock) {
                if (!isCurrentPlayer(snapshot.player, snapshot.token)) {
                    return@runCatching
                }
                isPlaybackStartPending = false
                currentPlayingUri = playbackUri
            }
            cancelStartTimeout()
            snapshot.request?.let(SoundPlayerState::onPlaybackStarted)
        }.onFailure { throwable ->
            appLogger.logException("SoundPlayerService", throwable, reason)
            terminatePlaybackUnavailable(reason, snapshot.soundIndex, snapshot.request)
        }
    }

    private fun safePausePlaybackFromFocusLoss() {
        val snapshot = snapshotPlaybackState() ?: return

        runCatching {
            if (snapshot.player.isPlaying) {
                snapshot.player.pause()
            }
            SoundPlayerState.onPlaybackPaused()
        }.onFailure { throwable ->
            appLogger.logException("SoundPlayerService", throwable, "audio_focus_loss_pause_failed")
            terminatePlaybackUnavailable("audio_focus_loss_pause_failed", snapshot.soundIndex, snapshot.request)
        }
    }

    private fun snapshotPlaybackState(): PlaybackSnapshot? {
        return synchronized(playerLock) {
            val player = mediaPlayer ?: return@synchronized null
            val uri = currentPlayingUri ?: pendingPlaybackUri?.let(Uri::parse)
            PlaybackSnapshot(
                player = player,
                uri = uri,
                soundIndex = pendingSoundIndex,
                token = playbackToken,
                request = SoundPlayerState.snapshot.value.activeRequest
            )
        }
    }

    private fun isCurrentPlayer(player: MediaPlayer, token: Int): Boolean {
        return synchronized(playerLock) {
            mediaPlayer === player && playbackToken == token
        }
    }

    private fun claimPendingPlayerOwnership(
        newPlayer: MediaPlayer,
        playbackRequest: PlaybackRequest
    ): Int? {
        return synchronized(playerLock) {
            if (!canCommitPendingPlayerOwnership(SoundPlayerState.snapshot.value.activeRequest, playbackRequest)) {
                return@synchronized null
            }

            playbackToken += 1
            mediaPlayer = newPlayer
            playbackToken
        }
    }

    private fun markPlaybackRequest(soundUri: Uri, soundIndex: Int?, playbackRequest: PlaybackRequest) {
        synchronized(playerLock) {
            pendingPlaybackUri = soundUri.toString()
            pendingSoundIndex = soundIndex
            isPlaybackStartPending = true
        }
        scheduleStartTimeout(playbackRequest)
        SoundPlayerState.onPlaybackRequestQueued(playbackRequest)
    }

    private suspend fun isAutoplaySuppressed(soundIndex: Int): Boolean {
        return if (soundIndex == GOODBYE_SOUND_INDEX) {
            userPreferencesRepository.autoplaySuppressed2.first()
        } else {
            userPreferencesRepository.autoplaySuppressed1.first()
        }
    }

    private suspend fun latchAutoplaySuppression(soundIndex: Int?) {
        if (soundIndex == null) {
            return
        }

        userPreferencesRepository.saveAutoplaySuppressed(soundIndex, true)
    }

    private suspend fun clearAutoplaySuppression(soundIndex: Int?) {
        if (soundIndex == null) {
            return
        }

        userPreferencesRepository.clearAutoplaySuppressed(soundIndex)
    }

    private fun enqueuePlaybackRequest(playbackRequest: PlaybackRequest): PlaybackRequest {
        val acceptedRequest = synchronized(playerLock) {
            playbackRequestCounter += 1L
            playbackRequest.withRequestId(playbackRequestCounter)
        }
        SoundPlayerState.onPlaybackRequestQueued(acceptedRequest)
        return acceptedRequest
    }

    private fun isActiveRequest(playbackRequest: PlaybackRequest): Boolean {
        return SoundPlayerState.snapshot.value.activeRequest == playbackRequest
    }

    private fun scheduleStartTimeout(playbackRequest: PlaybackRequest) {
        cancelStartTimeout()
        startTimeoutJob = serviceScope.launch {
            delay(PREPARE_START_TIMEOUT_MS)
            if (!isActiveRequest(playbackRequest)) {
                return@launch
            }

            appLogger.logAudio(
                "SoundPlayerService",
                "Playback prepare/start timed out for slot ${playbackRequest.soundIndex}"
            )
            terminatePlaybackUnavailable(
                reason = "prepare_start_timeout",
                soundIndex = playbackRequest.soundIndex,
                playbackRequest = playbackRequest
            )
        }
    }

    private fun cancelStartTimeout() {
        startTimeoutJob?.cancel()
        startTimeoutJob = null
    }

    private fun requestAudioFocus(): AudioFocusAcquireResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(PlaybackAudioProfile.focusGain)
                .setAudioAttributes(PlaybackAudioProfile.audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()

            audioFocusRequest = focusRequest
            val res = audioManager.requestAudioFocus(focusRequest)
            Log.d("SoundPlayerService", "AudioFocus Request Result: $res")
            return when (res) {
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> AudioFocusAcquireResult.GRANTED
                AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> AudioFocusAcquireResult.DELAYED
                else -> AudioFocusAcquireResult.FAILED
            }
        }

        @Suppress("DEPRECATION")
        val res = audioManager.requestAudioFocus(
            audioFocusChangeListener,
            PlaybackAudioProfile.legacyStreamType,
            PlaybackAudioProfile.focusGain
        )
        return if (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioFocusAcquireResult.GRANTED
        } else {
            AudioFocusAcquireResult.FAILED
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        }.onFailure { throwable ->
            appLogger.logException("SoundPlayerService", throwable, "Failed to abandon audio focus")
        }
        audioFocusRequest = null
    }

    private fun stopPlaybackSession(reason: String) {
        appLogger.logAudio("SoundPlayerService", "Stopping playback session ($reason)")
        cancelStartTimeout()
        SoundPlayerState.onPlaybackStopped()

        val playerToRelease = synchronized(playerLock) {
            val player = mediaPlayer
            mediaPlayer = null
            currentPlayingUri = null
            pendingPlaybackUri = null
            pendingSoundIndex = null
            isPlaybackStartPending = false
            player
        }

        abandonAudioFocus()

        runCatching {
            playerToRelease?.release()
        }.onFailure { throwable ->
            appLogger.logException("SoundPlayerService", throwable, "Failed to release MediaPlayer")
        }
    }

    private fun terminatePlaybackUnavailable(
        reason: String,
        soundIndex: Int?,
        playbackRequest: PlaybackRequest? = null
    ) {
        stopPlaybackSession("playback_unavailable:$reason")
        notifyPlaybackUnavailable(reason, soundIndex, playbackRequest)
    }

    private fun handlePlaybackFailure(
        failedUri: Uri,
        soundIndex: Int?,
        reason: String,
        playbackRequest: PlaybackRequest? = null
    ) {
        appLogger.logAudio("SoundPlayerService", "Playback failure for $failedUri ($reason)")
        stopPlaybackSession("failure:$reason")
        notifyPlaybackUnavailable(reason, soundIndex, playbackRequest)
    }

    private fun notifyPlaybackUnavailable(
        reason: String,
        soundIndex: Int?,
        playbackRequest: PlaybackRequest? = null
    ) {
        appLogger.logAudio(
            "SoundPlayerService",
            "notifyPlaybackUnavailable(soundIndex=$soundIndex, reason=$reason)"
        )
        serviceScope.launch {
            if (playbackRequest?.isUserInitiated == true) {
                val failureReason = if (userPreferencesRepository.isRemoteSyncBlocked.first()) {
                    CoreService.SOUND_FAILURE_REASON_SESSION_EXPIRED
                } else {
                    CoreService.SOUND_FAILURE_REASON_PLAYBACK_UNAVAILABLE
                }
                sendBroadcast(
                    Intent(CoreService.ACTION_SOUND_FAILED)
                        .setPackage(packageName)
                        .putExtra(CoreService.EXTRA_SOUND_FAILURE_REASON, failureReason)
                )
            }

            sendCancelAutoReturnBroadcast()
            stopSelf()
        }
    }

    private fun sendCancelAutoReturnBroadcast() {
        sendBroadcast(Intent(ACTION_CANCEL_AUTO_RETURN_HOME).setPackage(packageName))
    }

    private fun sendPlaybackFinishedBroadcast() {
        if (finishActivityOnCompletion) {
            sendBroadcast(Intent(ACTION_PLAYBACK_FINISHED).setPackage(packageName))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        appLogger.logService("SoundPlayerService", "Service destroyed")
        stopPlaybackSession("service_destroyed")
        serviceJob.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val name = "Sound Service"
        val descriptionText = "Playing startup sound"
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Car Startup Sound")
            .setContentText("Playing sound...")
            .setSmallIcon(R.drawable.ic_notifications)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private data class PlaybackSnapshot(
        val player: MediaPlayer,
        val uri: Uri?,
        val soundIndex: Int?,
        val token: Int,
        val request: PlaybackRequest?
    )

    private enum class AudioFocusAcquireResult {
        GRANTED,
        DELAYED,
        FAILED
    }

    companion object {
        const val CHANNEL_ID = "SoundPlayerServiceChannel"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP_SOUND = "com.example.carchatbot.service.ACTION_STOP_SOUND"
        const val ACTION_PLAYBACK_FINISHED = "com.example.carchatbot.service.ACTION_PLAYBACK_FINISHED"
        const val EXTRA_SOUND_URI_INDEX = "com.example.carchatbot.service.EXTRA_SOUND_URI_INDEX"
        const val EXTRA_PLAYBACK_REQUEST_INTENT =
            "com.example.carchatbot.service.EXTRA_PLAYBACK_REQUEST_INTENT"
        const val EXTRA_FINISH_ACTIVITY_ON_COMPLETION =
            "com.example.carchatbot.service.EXTRA_FINISH_ACTIVITY_ON_COMPLETION"
        const val EXTRA_FORCE_REPLAY = "com.example.carchatbot.service.EXTRA_FORCE_REPLAY"

        private const val DEFAULT_SOUND_INDEX = 1
        private const val GOODBYE_SOUND_INDEX = 2
        private const val PREPARE_START_TIMEOUT_MS = 8_000L
        private const val ACTION_CANCEL_AUTO_RETURN_HOME =
            "com.example.carchatbot.action.CANCEL_AUTO_RETURN_HOME"

        fun canCommitPendingPlayerOwnership(
            activeRequest: PlaybackRequest?,
            playbackRequest: PlaybackRequest
        ): Boolean = activeRequest == playbackRequest

        fun shouldPreemptStartupPlayback(
            playbackRequest: PlaybackRequest,
            startupPlaybackActive: Boolean
        ): Boolean {
            return playbackRequest.isUserInitiated && startupPlaybackActive
        }

        fun requestStopForStartupTakeover(context: Context) {
            val appContext = context.applicationContext
            SoundPlayerState.clearManualPlaybackForStartupTakeover()
            appContext.stopService(Intent(appContext, SoundPlayerService::class.java))
        }

        fun requestStopForServerRefresh(context: Context) {
            val appContext = context.applicationContext
            SoundPlayerState.clearManualPlaybackForTeardown()
            appContext.stopService(Intent(appContext, SoundPlayerService::class.java))
        }
    }
}
